import http from 'k6/http';
import { check, sleep } from 'k6';

// Day 35 부하 테스트. JMeter 플랜(codescope-repo-detail.jmx)과 동일한 시나리오
// (GET /api/repos/{id} 반복 호출)를 k6로도 준비 — 같은 InfluxDB(loadtest DB)로
// 결과를 보내면 같은 Grafana 대시보드로 비교해서 볼 수 있다.
//
// 실행 방법(InfluxDB v1 출력):
//   k6 run --out influxdb=http://localhost:8086/loadtest infra-monitoring/loadtest/repo-detail.js
//
// REPO_ID/HOST/PORT는 환경변수로 바꿀 수 있다:
//   k6 run --out influxdb=http://localhost:8086/loadtest -e REPO_ID=5 infra-monitoring/loadtest/repo-detail.js

const HOST = __ENV.HOST || 'localhost';
const PORT = __ENV.PORT || '8080';
// 62 = sindresorhus/awesome, DB에 실존하는 id (2026-08-17 실측:
// github_repository PK가 1이 아니라 62부터 시작 — codescope-repo-detail.jmx와
// 동일한 버그/동일한 수정, docs/performance/day35_backpressure.md 참고).
const REPO_ID = __ENV.REPO_ID || '62';
const BASE_URL = `http://${HOST}:${PORT}`;

// 실무형 부하 패턴(stages)으로 전환 — 고정 vus/duration 대신 단계별로
// 사용자 수를 늘렸다 줄인다:
//   0→10명 10초에 걸쳐 증가(ramp-up) → 갑자기 10명이 동시에 때리는 게 아니라
//     실제 트래픽처럼 서서히 유입되는 상황을 재현
//   10명 30초 유지(steady state) → 이 구간의 지표가 thresholds 판정의 핵심 구간
//   0명으로 10초에 걸쳐 감소(ramp-down) → 급격한 종료 대신 자연스러운 이탈 재현
// 세마포어 있는 상태(오늘 실측: 평균 9~16ms, 최대 326ms) 기준으로 보면 10 VUs는
// 크게 부담되는 수준이 아니라, 이 스테이지 자체가 "정상 상태에서 회귀가
// 없는가"를 보는 회귀 감지용 시나리오에 가깝다(극한 부하는 simulate-batch-load
// 실험 A/B/C 쪽 역할).
export const options = {
  stages: [
    { duration: '10s', target: 10 }, // ramp-up: 0 → 10 VUs
    { duration: '30s', target: 10 }, // steady state: 10 VUs 유지
    { duration: '10s', target: 0 },  // ramp-down: 10 → 0 VUs
  ],
  thresholds: {
    // 오늘 세마포어 있는 상태 실측(평균 9~16ms, 최대 325~326ms) 대비 여유 있게
    // 잡은 회귀 감지선. 이 선을 넘으면 "세마포어 없이 배포됐거나 풀이 다시
    // 고갈되고 있다"는 신호로 본다.
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'], // 실패율 1% 넘으면 fail
  },
};

export default function () {
  // tags: { name: 'repo-detail' } — Grafana에서 이 API 호출만 따로
  // 필터링해서 볼 수 있게 태깅(다른 엔드포인트를 나중에 추가해도 섞이지 않음).
  const res = http.get(`${BASE_URL}/api/repos/${REPO_ID}`, {
    tags: { name: 'repo-detail' },
  });
  check(res, {
    'status is 200': (r) => r.status === 200,
  });
  // sleep(1): 실제 사용자는 응답을 받자마자 바로 다음 요청을 안 쏘고 화면을
  // 보는 시간("think time")이 있다. 이게 없으면 VU 하나가 쉬지 않고 요청을
  // 연타하는 비현실적인 부하가 되어, 같은 VU 수라도 실제 트래픽보다 훨씬
  // 공격적인 부하로 왜곡된다.
  sleep(1);
}
