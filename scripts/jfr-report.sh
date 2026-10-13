#!/usr/bin/env bash
# JFR 녹화 파일(.jfr)에서 GC·할당·가상 스레드 pinning 분석 결과를 텍스트로 뽑는다.
#
# 사용: scripts/jfr-report.sh build/loadtest/app-g1-20261013-120000.jfr
# 결과: 같은 위치에 <파일명>.report.txt 생성(+ 화면 출력)
#
# 왜 JMC(GUI) 대신 jfr view인가: JDK 21에 내장된 CLI라 설치가 필요 없고,
#   결과가 텍스트로 남아 실험(G1 vs ZGC, 세마포어 on/off) 간 diff·문서화가
#   쉽다. 드릴다운이 필요하면 같은 .jfr을 JMC로 열면 된다.
set -euo pipefail

if [[ $# -ne 1 || ! -f "$1" ]]; then
  echo "사용법: $0 <recording.jfr>" >&2
  exit 1
fi

JFR_FILE="$1"
JFR_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}jfr"
REPORT="${JFR_FILE%.jfr}.report.txt"

# 뷰 순서 = 분석 순서: 설정 확인 → GC 요약 → pause 상세 → 할당 원인 → pinning → 핫스팟
VIEWS=(
  gc-configuration    # 어떤 GC/스레드 수로 돌았는가(실험 조건 확인)
  heap-configuration  # -Xms/-Xmx가 의도대로 고정됐는가
  gc                  # GC 횟수·누적/최대 pause 요약
  gc-pauses           # pause 분포(최대값이 p99 지연에 직결)
  gc-pause-phases     # pause 안에서 어느 단계가 오래 걸렸는가
  gc-cpu-time         # GC가 CPU를 얼마나 썼는가
  allocation-by-class # 무엇이 힙을 채우는가(Young GC 빈도의 원인)
  allocation-by-site  # 어디서 할당하는가(코드 위치)
  pinned-threads      # 가상 스레드 pinning(synchronized/네이티브 프레임)
  contention-by-site  # 락 경합 지점
  hot-methods         # CPU 핫스팟
)

{
  echo "# JFR 분석 리포트: $(basename "$JFR_FILE")"
  echo "# 생성: $(date '+%Y-%m-%d %H:%M:%S')"
  for view in "${VIEWS[@]}"; do
    echo
    echo "================================================================"
    echo "== $view"
    echo "================================================================"
    "$JFR_BIN" view --width 160 "$view" "$JFR_FILE" 2>&1 || echo "(이 뷰는 이벤트가 없어 비어 있음)"
  done
} | tee "$REPORT"

echo
echo "리포트 저장: $REPORT"
