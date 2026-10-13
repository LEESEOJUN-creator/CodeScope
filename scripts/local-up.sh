#!/usr/bin/env bash
# CodeScope 로컬 개발 환경을 한 번에 기동한다(macOS용, local-up.ps1의 bash 버전).
#
# 사용: scripts/local-up.sh              # 클러스터 + port-forward (앱은 bootRun으로 직접 실행)
#       scripts/local-up.sh --monitoring # + Grafana/Prometheus/InfluxDB(infra-monitoring)
#       scripts/local-up.sh --app        # + 앱도 클러스터에 배포(bootRun 불필요)
#       (옵션은 같이 쓸 수 있다: --app --monitoring)
# 정리: scripts/local-down.sh
#
# 하는 일:
#   1) Docker Desktop 확인(꺼져 있으면 실행 후 최대 2분 대기)
#   2) kind 클러스터('codescope') 확인, 없으면 생성 + k8s/overlays/kind 적용
#   3) kubectl port-forward 3개(postgres:5432, redis:6379, kafka-headless:9094)를
#      백그라운드로 실행
#   4) .env 필수 키 확인 — 비어 있으면 안내만 하고 계속 진행
#   5) --app: 이미지 로컬 빌드 → kind load → .env로 codescope-secrets 생성 →
#      k8s/overlays/local 적용 → port-forward 8080
#
# 왜 Apple Silicon에서 기본으로 codescope Deployment를 0으로 줄이는가: GHCR
#   이미지가 linux/amd64로만 빌드돼 arm64 노드에서는 "no match for platform in
#   manifest"로 ImagePullBackOff가 난다. --app은 이 문제를 로컬 빌드 이미지로
#   우회한다(k8s/overlays/local 참고).
#
# 왜 --app과 bootRun을 동시에 쓰면 안 되는가: 둘 다 같은 Kafka 컨슈머 그룹으로
#   붙어 파티션을 나눠 가지므로, 메시지 일부가 다른 쪽 앱에서 처리된다.
#   포트(8080)도 겹친다. 그래서 8080이 이미 사용 중이면 --app은 중단한다.
set -euo pipefail

CLUSTER_NAME="${CLUSTER_NAME:-codescope}"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="${HOME}/.codescope-logs"
# worktree처럼 .env가 다른 위치에 있으면 ENV_FILE=/path/.env 로 지정
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env}"
MONITORING=false
DEPLOY_APP=false
for arg in "$@"; do
  case "$arg" in
    --monitoring) MONITORING=true ;;
    --app) DEPLOY_APP=true ;;
    *) echo "알 수 없는 옵션: $arg (사용 가능: --monitoring, --app)" >&2; exit 1 ;;
  esac
done

# Docker Desktop을 brew 없이 설치하면 CLI가 PATH에 안 잡혀 있을 수 있다
export PATH="/Applications/Docker.app/Contents/Resources/bin:${HOME}/.docker/bin:${PATH}"

step() { printf '\033[36m==> %s\033[0m\n' "$1"; }
ok()   { printf '\033[32m  OK  %s\033[0m\n' "$1"; }
warn() { printf '\033[33m  !!  %s\033[0m\n' "$1"; }
port_open() { nc -z localhost "$1" >/dev/null 2>&1; }

mkdir -p "$LOG_DIR"

# ── 1) Docker Desktop 확인 ─────────────────────────────────────────
step 'Docker Desktop 확인 중...'
if ! docker info >/dev/null 2>&1; then
  warn 'Docker가 응답하지 않습니다. Docker Desktop을 실행합니다...'
  open -a Docker
  for i in $(seq 1 24); do
    sleep 5
    docker info >/dev/null 2>&1 && break
    echo "  ... Docker 기동 대기 중 ($((i * 5))초 경과)"
  done
  docker info >/dev/null 2>&1 || { echo 'Docker가 2분 안에 준비되지 않았습니다. 수동으로 켜고 다시 실행하세요.' >&2; exit 1; }
fi
ok 'Docker 준비됨'

# ── 2) kind 클러스터 확인/생성 ──────────────────────────────────────
step "kind 클러스터('${CLUSTER_NAME}') 확인 중..."
if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
  warn '클러스터가 없어 새로 만듭니다...'
  kind create cluster --name "$CLUSTER_NAME"
else
  ok "클러스터 '${CLUSTER_NAME}' 이미 존재"
fi
kubectl config use-context "kind-${CLUSTER_NAME}" >/dev/null

if [[ -z "$(kubectl get pods --no-headers 2>/dev/null)" ]]; then
  step 'Pod가 없어 k8s/overlays/kind 매니페스트를 적용합니다...'
  kubectl apply -k "${ROOT_DIR}/k8s/overlays/kind"
fi

# 이전에 --app으로 로컬 이미지를 배포해 둔 상태면 그대로 둔다
CURRENT_IMAGE="$(kubectl get deploy/codescope -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)"
if [[ "$(uname -m)" == "arm64" && "$CURRENT_IMAGE" != codescope-local:* ]] && ! $DEPLOY_APP; then
  kubectl scale deploy/codescope --replicas=0 >/dev/null
  ok 'Apple Silicon: codescope Pod는 0으로 유지(amd64 전용 이미지, 앱은 bootRun 또는 --app으로 실행)'
fi

step 'postgres/redis/kafka 준비 대기 중...'
kubectl rollout status deploy/postgres --timeout=300s
kubectl rollout status deploy/redis --timeout=300s
kubectl rollout status statefulset/kafka --timeout=300s

# ── 3) port-forward 3개 백그라운드 실행 ─────────────────────────────
step 'port-forward 시작 중 (postgres:5432, redis:6379, kafka:9094)...'
for target in postgres:5432 redis:6379 kafka-headless:9094; do
  svc="${target%:*}"
  port="${target#*:}"
  # 이미 떠 있는 포트는 건드리지 않는다 — 중복 실행하면 bind 에러로 새 프로세스만 바로 죽는다
  if port_open "$port"; then
    ok "포트 ${port} 이미 사용 중 — 기존 port-forward로 보고 건너뜀"
    continue
  fi
  nohup kubectl port-forward "svc/${svc}" "${port}:${port}" >"${LOG_DIR}/${svc}.log" 2>&1 &
  echo "  실행: kubectl port-forward svc/${svc} ${port}:${port}  (로그: ${LOG_DIR}/${svc}.log)"
done

sleep 3
for target in postgres:5432 redis:6379 kafka-headless:9094; do
  svc="${target%:*}"
  port="${target#*:}"
  if port_open "$port"; then
    ok "포트 ${port} (${svc}) 연결 확인"
  else
    warn "포트 ${port} (${svc}) 응답 없음 — 로그 확인: ${LOG_DIR}/${svc}.log"
  fi
done

# ── 4) 모니터링 스택(선택) ──────────────────────────────────────────
if $MONITORING; then
  step '모니터링 스택(infra-monitoring) 기동 중...'
  docker compose -f "${ROOT_DIR}/infra-monitoring/docker-compose.yml" up -d
  ok 'Grafana http://localhost:3001 / Prometheus http://localhost:9090 / InfluxDB http://localhost:8086'
fi

# ── 5) .env 필수 키 확인 ────────────────────────────────────────────
step ".env 확인 중... (${ENV_FILE})"
if [[ ! -f "$ENV_FILE" ]]; then
  warn '.env가 없습니다. cp .env.example .env 후 값을 채우세요.'
else
  for key in GITHUB_CLIENT_ID GITHUB_CLIENT_SECRET GITHUB_TOKEN JWT_SECRET ANTHROPIC_API_KEY; do
    if grep -qE "^${key}=.+" "$ENV_FILE"; then
      ok "${key} 설정됨"
    else
      warn "${key} 비어 있음"
    fi
  done
fi

# ── 6) 앱 클러스터 배포(선택) ───────────────────────────────────────
if $DEPLOY_APP; then
  [[ -f "$ENV_FILE" ]] || { echo "--app에는 .env가 필요합니다: ${ENV_FILE}" >&2; exit 1; }

  # 8080을 이미 누가 쓰고 있으면(bootRun 또는 기존 port-forward) 판단해서 처리
  if port_open 8080 && ! pgrep -f 'kubectl( --context [^ ]+)? port-forward svc/codescope ' >/dev/null; then
    echo '8080이 이미 사용 중입니다(bootRun 실행 중?). 같은 Kafka 컨슈머 그룹을 나눠 가지게 되므로 먼저 종료하세요.' >&2
    exit 1
  fi

  if port_open 3000 && ! pgrep -f 'kubectl( --context [^ ]+)? port-forward svc/codescope-frontend ' >/dev/null; then
    echo '3000이 이미 사용 중입니다(npm run dev / next start 실행 중?). 먼저 종료하세요.' >&2
    exit 1
  fi

  # 커밋 SHA 태그 — 작업 트리에 미커밋 변경이 있으면 시각을 붙여 매번 새 태그로
  # 만든다(같은 태그를 재사용하면 imagePullPolicy: Never라 Pod가 옛 이미지를 쓴다)
  SHA="$(git -C "$ROOT_DIR" rev-parse --short HEAD)"
  image_tag() {
    if [[ -n "$(git -C "$ROOT_DIR" status --porcelain -- "$@")" ]]; then
      echo "${SHA}-dirty-$(date +%s)"
    else
      echo "$SHA"
    fi
  }
  APP_IMAGE="codescope-local:$(image_tag src build.gradle Dockerfile)"
  FRONT_IMAGE="codescope-frontend-local:$(image_tag frontend)"

  step "앱 이미지 빌드 중 (${APP_IMAGE})..."
  docker build -t "$APP_IMAGE" "$ROOT_DIR"
  step "프론트엔드 이미지 빌드 중 (${FRONT_IMAGE})..."
  docker build -t "$FRONT_IMAGE" "${ROOT_DIR}/frontend"
  kind load docker-image "$APP_IMAGE" "$FRONT_IMAGE" --name "$CLUSTER_NAME"

  step '.env → codescope-secrets 반영 중...'
  # apply로 덮어써서 .env 변경분이 재실행만으로 반영되게 한다
  kubectl create secret generic codescope-secrets --from-env-file="$ENV_FILE" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  ok "시크릿 키: $(kubectl get secret codescope-secrets -o go-template='{{range $k, $v := .data}}{{$k}} {{end}}')"

  step '앱/프론트엔드 배포 중 (k8s/overlays/local)...'
  kubectl apply -k "${ROOT_DIR}/k8s/overlays/local" >/dev/null
  kubectl set image deploy/codescope "codescope=${APP_IMAGE}" >/dev/null
  kubectl set image deploy/codescope-frontend "frontend=${FRONT_IMAGE}" >/dev/null
  kubectl scale deploy/codescope deploy/codescope-frontend --replicas=1 >/dev/null
  # 시크릿만 바뀌고 이미지가 같으면 Pod가 재시작되지 않으므로 명시적으로 재시작
  kubectl rollout restart deploy/codescope >/dev/null
  kubectl rollout status deploy/codescope --timeout=300s
  kubectl rollout status deploy/codescope-frontend --timeout=180s

  # port-forward는 svc가 아니라 시작 시점의 Pod 하나에 붙는다. 재배포로 Pod가
  # 교체되면 기존 port-forward는 종료 중인 옛 Pod를 가리키다 끊기므로 다시 연다
  pkill -f 'kubectl( --context [^ ]+)? port-forward svc/codescope ' || true
  pkill -f 'kubectl( --context [^ ]+)? port-forward svc/codescope-frontend ' || true
  sleep 1
fi

# ── 7) 클러스터 앱 port-forward ─────────────────────────────────────
# --app 없이 재실행해도(예: 재부팅 후) 이미 배포된 앱이 있으면 접속 경로를 복구한다
for target in codescope:8080 codescope-frontend:3000; do
  svc="${target%:*}"
  port="${target#*:}"
  replicas="$(kubectl get "deploy/${svc}" -o jsonpath='{.spec.replicas}' 2>/dev/null || echo 0)"
  [[ "${replicas:-0}" -gt 0 ]] || continue
  if ! port_open "$port"; then
    # tmux가 있으면 실행 도구/터미널이 끝나도 접속을 유지한다.
    if command -v tmux >/dev/null 2>&1; then
      tmux new-session -d -s "codescope-pf-${svc}" kubectl --context "kind-${CLUSTER_NAME}" port-forward "svc/${svc}" "${port}:${port}"
    else
      nohup kubectl port-forward "svc/${svc}" "${port}:${port}" >"${LOG_DIR}/${svc}.log" 2>&1 &
    fi
    sleep 3
  fi
  port_open "$port" && ok "${svc} http://localhost:${port}" || warn "${port} 응답 없음 — 로그 확인: ${LOG_DIR}/${svc}.log"
done

echo
if [[ "$(kubectl get deploy/codescope -o jsonpath='{.spec.replicas}' 2>/dev/null || echo 0)" -gt 0 ]]; then
  printf '\033[32m준비 완료 — 클러스터에서 실행 중: 화면 http://localhost:3000 / API http://localhost:8080\033[0m\n'
  echo '  로그: kubectl logs -f deploy/codescope  (프론트: deploy/codescope-frontend)'
  echo '  코드나 .env를 바꿨으면 --app으로 다시 실행하면 새 이미지로 재배포됩니다.'
else
  printf '\033[32m준비 완료 — 이제 앱을 실행하세요: ./gradlew bootRun\033[0m\n'
  echo '  (부하 테스트용: ./gradlew bootRunLoadTest)'
  echo '  프론트엔드: cd frontend && npm run dev  → http://localhost:3000'
fi
echo
echo '종료하려면: scripts/local-down.sh'
