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
const REPO_ID = __ENV.REPO_ID || '1';
const BASE_URL = `http://${HOST}:${PORT}`;

// JMeter 플랜의 스레드 그룹(10 threads, ramp-up 5s, 50 loops)과 대략 맞춘
// 기본 부하 프로파일 — k6 옵션으로 필요하면 --vus/--duration으로 덮어쓸 수 있음.
export const options = {
  vus: 10,
  duration: '30s',
  thresholds: {
    http_req_failed: ['rate<0.05'], // 실패율 5% 넘으면 실패로 표시(참고용 기본값)
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/api/repos/${REPO_ID}`);
  check(res, {
    'status is 200': (r) => r.status === 200,
  });
  sleep(1);
}
