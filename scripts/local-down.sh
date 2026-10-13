#!/usr/bin/env bash
# local-up.sh로 띄운 로컬 개발 환경을 정리한다(macOS용, local-down.ps1의 bash 버전).
#
# 사용: scripts/local-down.sh                   # port-forward만 정리, 클러스터 유지
#       scripts/local-down.sh --delete-cluster  # + kind 클러스터 삭제(Pod/데이터 전부 사라짐)
#
# 모니터링 스택(infra-monitoring)은 건드리지 않는다 — 끄려면:
#   docker compose -f infra-monitoring/docker-compose.yml down
set -euo pipefail

CLUSTER_NAME="${CLUSTER_NAME:-codescope}"
export PATH="/Applications/Docker.app/Contents/Resources/bin:${HOME}/.docker/bin:${PATH}"

step() { printf '\033[36m==> %s\033[0m\n' "$1"; }
ok()   { printf '\033[32m  OK  %s\033[0m\n' "$1"; }

step 'port-forward 프로세스 정리 중...'
# 커맨드라인이 "kubectl port-forward svc/"인 것만 골라 죽인다 —
# 다른 목적의 kubectl 프로세스(로그 확인 등)를 실수로 같이 죽이지 않기 위함
if pkill -f 'kubectl port-forward svc/'; then
  ok 'port-forward 종료함'
else
  ok '정리할 port-forward 프로세스가 없습니다.'
fi

# local-up.sh --app으로 띄운 앱 Pod도 내린다 — 남겨두면 이후 bootRun과 같은
# Kafka 컨슈머 그룹을 나눠 가진다. 이미지/시크릿은 남으므로 --app으로 다시 올릴 수 있다
for d in codescope codescope-frontend; do
  if kubectl get "deploy/${d}" >/dev/null 2>&1; then
    kubectl scale "deploy/${d}" --replicas=0 >/dev/null
    ok "클러스터 ${d} Pod를 0으로 줄임"
  fi
done

if [[ "${1:-}" == "--delete-cluster" ]]; then
  step "kind 클러스터('${CLUSTER_NAME}') 삭제 중..."
  kind delete cluster --name "$CLUSTER_NAME"
  ok '클러스터 삭제 완료'
else
  echo
  echo "kind 클러스터('${CLUSTER_NAME}')는 유지합니다. 완전히 지우려면: scripts/local-down.sh --delete-cluster"
fi

echo
printf '\033[32m정리 완료.\033[0m\n'
