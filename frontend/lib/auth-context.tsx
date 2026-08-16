'use client';

import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import { getAccessToken, setAccessToken as storeAccessToken } from './token-store';

type AuthContextValue = {
  accessToken: string | null;
  isAuthenticated: boolean;
  setAccessToken: (token: string | null) => void;
};

const AuthContext = createContext<AuthContextValue | null>(null);

// Server Component(app/layout.tsx)는 React Context를 못 쓰므로, Context
// Provider 자체는 반드시 별도 Client Component로 분리해야 한다
// (Next.js 공식 가이드: "Context providers" 패턴).
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [accessToken, setAccessTokenState] = useState<string | null>(() => getAccessToken());

  const setAccessToken = useCallback((token: string | null) => {
    storeAccessToken(token);
    setAccessTokenState(token);
  }, []);

  const value = useMemo(
    () => ({ accessToken, isAuthenticated: accessToken !== null, setAccessToken }),
    [accessToken, setAccessToken]
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
