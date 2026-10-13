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
//
// 동시에 여러 요청이 401을 받아도 refresh는 한 번만 나가게 진행 중인 Promise를
// 공유한다 — refresh는 토큰을 회전시키므로, 두 번 나가면 먼저 끝난 쪽이 받은
// 토큰을 나중 요청이 무효화(재사용 감지)해 로그아웃될 수 있다.
let refreshInFlight: Promise<string | null> | null = null;

export function refreshAccessToken(): Promise<string | null> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      try {
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
      } catch {
        return null;
      } finally {
        refreshInFlight = null;
      }
    })();
  }
  return refreshInFlight;
}

// 모든 백엔드 호출이 거치는 공통 통로.
// 1) 메모리에 있는 Access Token을 Authorization 헤더에 자동 첨부
// 2) 401을 받으면 refreshAccessToken()으로 갱신 시도 후 원래 요청을 "1회만" 재시도
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

export type ApiResult<T> = {
  ok: boolean;
  status: number;
  data: T | null;
  message: string | null;
  // 503(LLM 과부하, LlmOverloadedException)일 때 서버가 알려준 재시도 대기 초
  retryAfter: number | null;
};

// apiFetch + JSON 파싱 + 실패 메시지 정리를 한 번에. 화면 코드는 상태 코드별
// 분기(401 로그인 유도, 503 재시도 안내)에만 집중하면 된다.
export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<ApiResult<T>> {
  try {
    const res = await apiFetch(path, init);
    const body: ApiResponse<T> | null =
      res.status === 204 ? null : await res.json().catch(() => null);
    const retryAfterHeader = res.headers.get('Retry-After');
    return {
      ok: res.ok,
      status: res.status,
      data: body?.data ?? null,
      message: res.ok ? null : (body?.message ?? `요청에 실패했습니다 (HTTP ${res.status})`),
      retryAfter: retryAfterHeader ? Number(retryAfterHeader) : null,
    };
  } catch {
    return {
      ok: false,
      status: 0,
      data: null,
      message: '서버에 연결할 수 없습니다. 백엔드가 실행 중인지 확인해주세요.',
      retryAfter: null,
    };
  }
}

export function jsonBody(value: unknown): RequestInit {
  return {
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(value),
  };
}
