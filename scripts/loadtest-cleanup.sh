#!/usr/bin/env bash
# stub provider로 돌린 부하테스트가 남긴 가짜 데이터를 지운다.
#
# 왜 필요한가: TrendAnalysisService는 분석 결과를 trend_scores(DB)와 Redis 캐시
#   (trendAnalysis, TTL 1시간)에 저장한다. stub으로 부하를 걸면 "[stub] ..." 텍스트가
#   그대로 남아, 이후 실제 Claude로 띄운 앱이 최대 1시간 동안 캐시된 stub 응답을
#   사용자에게 내보낸다.
#
# 지우는 것: trend_scores 중 analysis_text가 '[stub]'로 시작하는 행 + Redis의
#   trendAnalysis 캐시 전체(캐시는 재계산 가능한 파생 데이터라 전부 지워도 안전).
# 지우지 않는 것: github_repository, repo_embeddings 등 수집 데이터.
#
# 반드시 앱 프로세스가 "완전히 끝난 뒤" 실행할 것: 정상 종료(graceful shutdown)는
#   포트를 먼저 닫고, 세마포어 대기열에 남아 있던 요청을 끝까지 처리하면서 DB에
#   쓴다. 포트가 닫히자마자 정리하면 그 뒤에 끝난 요청의 stub 행이 남는다(실측 4건).
#   그래서 앱 프로세스가 살아 있으면 정리하지 않고 중단한다.
# 전제: kind 클러스터(kubectl 컨텍스트)에 postgres/redis Deployment가 떠 있음.
# 사용: scripts/loadtest-cleanup.sh
set -euo pipefail

if pgrep -f "com.codescope.CodescopeApplication" >/dev/null; then
  echo "앱 프로세스가 아직 실행 중입니다 — 완전히 종료된 뒤 다시 실행하세요." >&2
  exit 1
fi

echo "▶ trend_scores의 stub 분석 삭제"
kubectl exec deploy/postgres -- psql -U postgres -d codescope -tAc \
  "delete from trend_scores where analysis_text like '[stub]%'"

echo "▶ Redis trendAnalysis 캐시 삭제"
kubectl exec deploy/redis -- sh -c \
  "redis-cli --scan --pattern 'trendAnalysis*' | xargs -r redis-cli del" >/dev/null
remaining=$(kubectl exec deploy/redis -- redis-cli --scan --pattern 'trendAnalysis*' | wc -l | tr -d ' ')
echo "  남은 trendAnalysis 키: ${remaining}"
