'use client';

import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { apiFetch, apiRequest } from './api-client';
import { useAuth } from './auth-context';
import type { Favorite } from './types';

// 즐겨찾기 여부 조회 + 낙관적 토글. 비로그인 사용자가 별을 누르면 로그인 화면으로 보낸다.
export function useFavorites() {
  const router = useRouter();
  const { isAuthenticated } = useAuth();
  const [favoritedIds, setFavoritedIds] = useState<Set<number>>(new Set());

  useEffect(() => {
    if (!isAuthenticated) {
      queueMicrotask(() => setFavoritedIds(new Set()));
      return;
    }
    let cancelled = false;
    apiRequest<Favorite[]>('/api/users/me/bookmarks').then((res) => {
      if (!cancelled && res.ok) {
        setFavoritedIds(new Set((res.data ?? []).map((f) => f.repoId)));
      }
    });
    return () => {
      cancelled = true;
    };
  }, [isAuthenticated]);

  const toggle = useCallback(
    async (repoId: number) => {
      if (!isAuthenticated) {
        router.push('/login');
        return;
      }
      const wasFavorited = favoritedIds.has(repoId);
      const flip = (prev: Set<number>, add: boolean) => {
        const next = new Set(prev);
        if (add) next.add(repoId);
        else next.delete(repoId);
        return next;
      };

      // 응답을 기다리지 않고 먼저 반영(낙관적 업데이트), 실패하면 되돌린다
      setFavoritedIds((prev) => flip(prev, !wasFavorited));
      const res = await apiFetch(`/api/users/me/bookmarks/${repoId}`, {
        method: wasFavorited ? 'DELETE' : 'POST',
      }).catch(() => null);
      if (!res || !res.ok) {
        setFavoritedIds((prev) => flip(prev, wasFavorited));
      }
    },
    [favoritedIds, isAuthenticated, router]
  );

  return {
    isFavorited: (repoId: number) => favoritedIds.has(repoId),
    toggle,
  };
}
