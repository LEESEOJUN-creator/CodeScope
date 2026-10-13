'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from './auth-context';

// 로그인 전용 화면(즐겨찾기, 내 스택)에서 사용. 세션 복구 시도(ready)가 끝난 뒤에도
// 비로그인이면 /login으로 보낸다 — ready 전에 보내면 새로고침마다 로그인 화면으로
// 튕기는 문제가 생긴다.
export function useRequireAuth(): boolean {
  const router = useRouter();
  const { ready, isAuthenticated } = useAuth();

  useEffect(() => {
    if (ready && !isAuthenticated) {
      router.replace('/login');
    }
  }, [ready, isAuthenticated, router]);

  return ready && isAuthenticated;
}
