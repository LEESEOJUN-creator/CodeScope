import { getAccessToken, setAccessToken } from './token-store';
import type { ApiResponse } from './types';

export const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

// GET /api/auth/refresh 재현이 아니라 POST — RefreshTokenService.validateAndRotate가
// 매 호출마다 Refresh Token을 회전(rotate)시키는 부수효과가 있어 GET(멱등해야 함)은
// 부적절하다는 게 백엔드 쪽 기존 설계 결정(AuthController 참고).
//
// 쿠키(Refresh Token)만으로 인증하므로 credentials: 'include' 필수 — 이게 없으면
// 브라우저가 HttpOnly 쿠키를 요청에 아예 안 실어 보낸다.
export async function refreshAccessToken(): Promise<string | null> {
  const res = await fetch(`${API_BASE_URL}/api/auth/refresh`, {
    method: 'POST',
    credentials: 'include',
  });

  if (!res.ok) {
    setAccessToken(null);
    return null;
  }

  const body: ApiResponse<{ accessToken: string }> = await res.json();
  const token = body.data?.accessToken ?? null;
  setAccessToken(token);
  return token;
}

// 모든 백엔드 호출이 거치는 공통 통로.
// 1) 메모리에 있는 Access Token을 Authorization 헤더에 자동 첨부
// 2) 401을 받으면 refreshAccessToken()으로 갱신 시도 후 원래 요청을 "1회만" 재시도
//    (재시도까지 실패하면 그대로 401 Response를 반환 — /login 리다이렉트 여부는
//    호출부(useApiData 등)의 책임으로 남긴다. 이 함수는 순수 fetch 래퍼라
//    Next.js 라우터에 의존하지 않는다)
export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const requestWithToken = (token: string | null): RequestInit => {
    const headers = new Headers(init.headers);
    if (token) {
      headers.set('Authorization', `Bearer ${token}`);
    }
    return { ...init, headers, credentials: 'include' };
  };

  let response = await fetch(`${API_BASE_URL}${path}`, requestWithToken(getAccessToken()));

  if (response.status === 401) {
    const refreshed = await refreshAccessToken();
    if (refreshed) {
      response = await fetch(`${API_BASE_URL}${path}`, requestWithToken(refreshed));
    }
  }

  return response;
}
