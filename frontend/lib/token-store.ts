// Access Token을 오직 메모리에만 보관한다(localStorage/sessionStorage 미사용).
// 왜: XSS 공격이 발생해도 새로고침 한 번이면 토큰이 사라지는 게 그나마 안전한
// 절충안이다 — localStorage에 넣으면 탭을 새로 열어도 토큰이 살아있어 공격
// 표면이 넓어진다. 대신 Refresh Token은 HttpOnly 쿠키(JS가 아예 못 읽음)에
// 있어서, 새로고침해도 /api/auth/refresh로 다시 Access Token을 받아올 수 있다
// (자세한 흐름은 app/callback/page.tsx 참고).
//
// 왜 React state가 아니라 모듈 전역 변수인가: api-client.ts처럼 React 컴포넌트가
// 아닌 순수 함수(fetch 래퍼)도 최신 토큰 값을 동기적으로 읽어야 한다. React
// state는 컴포넌트 트리 밖에서 읽을 수 없어서, "진짜 값의 출처"는 이 모듈에
// 두고 auth-context.tsx가 이 값의 변경을 React state로 미러링해 UI 리렌더링을
// 트리거하는 구조로 나눴다.
let accessToken: string | null = null;

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string | null): void {
  accessToken = token;
}
