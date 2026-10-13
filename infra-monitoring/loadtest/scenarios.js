import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

// CodeScope 다중 경로 부하테스트 — GC/JFR/배압 관찰용.
// repo-detail.js(단일 엔드포인트 회귀 감지)와 달리, 실제 사용 경로를 성격별로
// 나눠 "어느 경로가 힙·커넥션 풀·세마포어를 어떻게 소모하는가"를 본다.
//
// 앱은 반드시 ./gradlew bootRunLoadTest(stub LLM)로 띄울 것 — 실제 Claude로
// 띄운 상태에서 recommend/trend를 돌리면 요청 수만큼 API 비용이 나간다.
//
// 실행(결과는 InfluxDB → Grafana "k6 Load Testing Results"):
//   k6 run --out influxdb=http://localhost:8086/loadtest infra-monitoring/loadtest/scenarios.js
//   k6 run -e SCENARIO=recommend -e RATE=20 ... scenarios.js   # 단일 시나리오
//   k6 run -e SCENARIO=soak -e DURATION=30m ... scenarios.js   # 장시간(힙 누수/Old 증가 관찰)
//
// 왜 constant-arrival-rate(open model)인가: 기존 stages(VU 기반, closed
//   model)는 서버가 느려지면 VU가 응답을 기다리느라 요청을 덜 보낸다 —
//   부하가 저절로 줄어 지연이 실제보다 좋아 보이는 coordinated omission이
//   생긴다. GC pause나 세마포어 대기로 서버가 멈칫할 때 "그래도 요청은 계속
//   들어온다"를 재현해야 p99와 대기열 증가를 정직하게 볼 수 있다.

const BASE_URL = `http://${__ENV.HOST || 'localhost'}:${__ENV.PORT || '8080'}`;
// 쉼표 구분 레포 id 목록. DB에 실존하는 id여야 한다(PK가 62부터 시작했던
// 전례가 있음 — docs/performance/day35_backpressure.md 참고).
const REPO_IDS = (__ENV.REPO_IDS || '62').split(',').map((s) => s.trim());
const STACKS = [
  'Java, Spring Boot, Kafka',
  'TypeScript, React, Next.js',
  'Python, PyTorch',
  'Go, Kubernetes',
  'Rust, WebAssembly',
];
const KEYWORDS = ['spring', 'react', 'llm', 'kafka', 'vector'];

const RATE = Number(__ENV.RATE || 20); // 초당 요청 수(시나리오별)
const DURATION = __ENV.DURATION || '2m';
const SELECTED = __ENV.SCENARIO || 'mixed';

const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];

// LLM 경로는 과부하 시 503 + Retry-After로 빠르게 거절한다(load shedding,
// LlmAdmissionControl). 이건 서버 오류가 아니라 의도된 배압 응답이라
// http_req_failed에 넣지 않고, 거절 비율을 llm_shed_rate로 따로 본다.
const LLM_EXPECTED = http.expectedStatuses(200, 503);
const llmShedRate = new Rate('llm_shed_rate');

// preAllocatedVUs/maxVUs: 응답이 느려져도 목표 RATE를 유지하려면 동시
// 진행 중인 요청 수만큼 VU가 필요하다(필요 VU ≈ RATE × 평균 응답시간(초)).
// recommend/trend는 stub 지연(기본 2초)이 있어 더 많이 잡는다.
const SCENARIOS = {
  // DB + Redis 캐시 경로(LLM 없음). 순수 앱/DB 처리량 기준선.
  browse: {
    executor: 'constant-arrival-rate', exec: 'browse',
    rate: RATE, timeUnit: '1s', duration: DURATION,
    preAllocatedVUs: 20, maxVUs: 100,
    tags: { path: 'browse' },
  },
  // RAG 경로: 질의 임베딩(세마포어) → pgvector 검색(DB) → LLM 생성(세마포어).
  // claudeSemaphore 대기열과 가상 스레드 수가 함께 늘어나는 모습을 본다.
  recommend: {
    executor: 'constant-arrival-rate', exec: 'recommend',
    rate: Math.max(1, Math.floor(RATE / 4)), timeUnit: '1s', duration: DURATION,
    preAllocatedVUs: 30, maxVUs: 300,
    tags: { path: 'recommend' },
  },
  // 트렌드 분석: 레포 id가 적으면 대부분 Redis 캐시 히트, 많으면 미스(LLM) 비율이 오른다.
  trend: {
    executor: 'constant-arrival-rate', exec: 'trend',
    rate: Math.max(1, Math.floor(RATE / 4)), timeUnit: '1s', duration: DURATION,
    preAllocatedVUs: 20, maxVUs: 200,
    tags: { path: 'trend' },
  },
};

function buildScenarios() {
  if (SELECTED === 'mixed') return SCENARIOS;
  if (SELECTED === 'soak') {
    // 장시간 혼합 부하를 낮은 강도로 — Old 영역이 계속 오르면 누수 의심
    return Object.fromEntries(Object.entries(SCENARIOS).map(([k, v]) => [k, { ...v, duration: __ENV.DURATION || '30m' }]));
  }
  if (!SCENARIOS[SELECTED]) throw new Error(`알 수 없는 SCENARIO: ${SELECTED}`);
  return { [SELECTED]: SCENARIOS[SELECTED] };
}

export const options = {
  scenarios: buildScenarios(),
  // 경로별로 기대 지연이 달라 임계치를 따로 둔다. browse는 repo-detail.js와
  // 같은 500ms. LLM 경로는 "대기 상한 5초 + 생성 2~3초"라 8초 — 대기 상한
  // 덕분에 과부하에서도 이 선을 넘지 않아야 한다(넘으면 shedding이 안 도는 것).
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    'http_req_duration{path:browse}': ['p(95)<500'],
    'http_req_duration{path:recommend}': ['p(95)<8000'],
    'http_req_duration{path:trend}': ['p(95)<8000'],
    // arrival-rate가 VU 부족으로 목표를 못 채우면 dropped_iterations가 쌓인다 —
    // 서버가 감당 못 해 응답이 밀리고 있다는 직접적인 신호.
    'dropped_iterations': ['count<1'],
  },
};

const ok = (res) => check(res, { 'status is 200': (r) => r.status === 200 });

export function browse() {
  const roll = Math.random();
  if (roll < 0.5) {
    ok(http.get(`${BASE_URL}/api/repos/${pick(REPO_IDS)}`, { tags: { name: 'repo-detail' } }));
  } else if (roll < 0.8) {
    ok(http.get(`${BASE_URL}/api/repos/search?keyword=${pick(KEYWORDS)}&page=0&size=20`, { tags: { name: 'repo-search' } }));
  } else {
    ok(http.get(`${BASE_URL}/api/repos/trending?limit=20`, { tags: { name: 'repo-trending' } }));
  }
}

// 200(정상) 또는 503(의도된 거절)만 정상으로 보고, 거절 비율을 기록한다.
const llmOk = (res) => {
  llmShedRate.add(res.status === 503);
  return check(res, { 'status is 200 or 503(shed)': (r) => r.status === 200 || r.status === 503 });
};

export function recommend() {
  llmOk(http.get(`${BASE_URL}/api/recommend?stack=${encodeURIComponent(pick(STACKS))}`, {
    tags: { name: 'recommend' },
    timeout: '60s',
    responseCallback: LLM_EXPECTED,
  }));
}

export function trend() {
  llmOk(http.get(`${BASE_URL}/api/trends/analysis?repoId=${pick(REPO_IDS)}`, {
    tags: { name: 'trend-analysis' },
    timeout: '60s',
    responseCallback: LLM_EXPECTED,
  }));
}
