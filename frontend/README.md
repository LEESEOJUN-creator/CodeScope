# CodeScope Frontend (Day 30~31 최소 시연)

백엔드(`src/`)와 같은 레포 안의 모노레포 하위 디렉토리. Next.js App Router 사용.

## 화면 구성

- `/login` — GitHub OAuth 로그인 버튼. `?error=...` 쿼리로 로그인 실패 사유 표시
- `/callback` — OAuth 성공 후 리다이렉트 목적지. `POST /api/auth/refresh`로 Access
  Token을 받아 메모리(React Context)에 저장한 뒤 `/trending`으로 이동
- `/trending` — 트렌드 레포 목록(`GET /api/repos/trending`)
- `/repos/[id]` — 레포 상세 + AI "왜 뜨는가" 분석(`GET /api/repos/{id}`,
  `GET /api/trends/analysis?repoId=`)
- `/recommend` — 로그인 사용자의 관심 스택 기반 AI 추천(`GET /api/recommend`)

## 인증 흐름

Access Token은 **메모리에만** 보관한다(localStorage 미사용, XSS 노출 표면 축소).
Refresh Token은 백엔드가 HttpOnly 쿠키로만 관리하므로 이 프론트 코드에서는
아예 접근할 수 없다. `lib/api-client.ts`의 `apiFetch()`가 모든 API 호출에
`Authorization: Bearer <토큰>`을 자동으로 붙이고, 401을 받으면
`POST /api/auth/refresh`로 토큰을 갱신해 원 요청을 1회 재시도한다. 그래도
실패하면 호출부(`lib/use-api-data.ts`)가 `/login`으로 보낸다.

## 로컬 실행

```bash
cp .env.example .env.local   # NEXT_PUBLIC_API_BASE_URL 확인/수정
npm install
npm run dev
```

백엔드(`http://localhost:8080`)가 먼저 떠 있어야 하고, `application.yaml`의
`cors.allowed-origins`가 이 프론트 오리진(`http://localhost:3000`)과 일치해야 한다.

## Day 30~31 스코프 원칙

무한스크롤/다크모드/반응형/차트/애니메이션은 의도적으로 배제했다. 4개 화면의
기능 흐름(로그인 → 콜백 → 트렌드 → 상세/추천)이 실제로 동작하는 것이 핵심이고
디자인은 부가 요소.
