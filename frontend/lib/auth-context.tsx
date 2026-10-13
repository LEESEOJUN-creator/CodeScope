'use client';

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { apiFetch, apiRequest, refreshAccessToken } from './api-client';
import { getAccessToken, setAccessToken as storeAccessToken } from './token-store';
import type { UserProfile } from './types';

type AuthContextValue = {
  accessToken: string | null;
  isAuthenticated: boolean;
  // 앱 시작 시 refresh 쿠키로 세션 복구를 시도했는지. false인 동안에는 로그인
  // 여부를 아직 모르는 상태라, 보호 화면이 섣불리 /login으로 보내지 않게 한다.
  ready: boolean;
  user: UserProfile | null;
  setAccessToken: (token: string | null) => void;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [accessToken, setAccessTokenState] = useState<string | null>(() => getAccessToken());
  const [ready, setReady] = useState(false);
  const [user, setUser] = useState<UserProfile | null>(null);

  const setAccessToken = useCallback((token: string | null) => {
    storeAccessToken(token);
    setAccessTokenState(token);
  }, []);

  // Access Token은 XSS 노출을 줄이려고 메모리에만 둔다(token-store). 그래서 새로고침하면
  // 사라지는데, HttpOnly refresh 쿠키가 살아 있으면 여기서 조용히 다시 발급받아
  // "새로고침하면 로그아웃된 것처럼 보이는" 문제를 막는다.
  useEffect(() => {
    let cancelled = false;
    refreshAccessToken().then((token) => {
      if (cancelled) return;
      if (token) setAccessToken(token);
      setReady(true);
    });
    return () => {
      cancelled = true;
    };
  }, [setAccessToken]);

  useEffect(() => {
    if (!accessToken) {
      queueMicrotask(() => setUser(null));
      return;
    }
    let cancelled = false;
    apiRequest<UserProfile>('/api/users/me').then((res) => {
      if (!cancelled) setUser(res.ok ? res.data : null);
    });
    return () => {
      cancelled = true;
    };
  }, [accessToken]);

  const logout = useCallback(async () => {
    await apiFetch('/api/auth/logout', { method: 'POST' }).catch(() => undefined);
    setAccessToken(null);
  }, [setAccessToken]);

  const value = useMemo(
    () => ({
      accessToken,
      isAuthenticated: accessToken !== null,
      ready,
      user,
      setAccessToken,
      logout,
    }),
    [accessToken, ready, user, setAccessToken, logout]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth는 AuthProvider 하위에서만 사용할 수 있습니다.');
  }
  return ctx;
}
