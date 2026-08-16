'use client';

import { useCallback, useEffect, useState } from 'react';
import { apiFetch } from './api-client';
import type { ApiResponse, Favorite } from './types';

// 트렌드/상세 화면의 ★ 토글 버튼이 공유하는 "지금 로그인한 사용자가 즐겨찾기한
// repoId 집합" 상태. 목록 전체를 다시 그릴 필요 없이 멤버십(id 존재 여부)만
// 알면 되므로 FavoriteResponse 전체가 아니라 Set<number>로 축약해서 들고 있는다.
export function useFavorites() {
  const [favoritedIds, setFavoritedIds] = useState<Set<number>>(new Set());
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      const res = await apiFetch('/api/users/me/bookmarks');
      if (cancelled) return;
      if (res.ok) {
        const body: ApiResponse<Favorite[]> = await res.json();
        setFavoritedIds(new Set((body.data ?? []).map((f) => f.repoId)));
      }
      setLoaded(true);
    }

    load();

    return () => {
      cancelled = true;
    };
  }, []);

  // 낙관적 업데이트(Optimistic UI) — 클릭 즉시 별 표시를 바꾸고, 서버 응답이
  // 실패하면 그때 되돌린다. UNIQUE 제약/존재하지 않는 즐겨찾기 삭제 등으로
  // 400/404가 나는 경우도 이 롤백 경로로 자연스럽게 처리된다.
  const toggle = useCallback(
    async (repoId: number) => {
      const wasFavorited = favoritedIds.has(repoId);

      setFavoritedIds((prev) => {
        const next = new Set(prev);
        if (wasFavorited) {
          next.delete(repoId);
        } else {
          next.add(repoId);
        }
        return next;
      });

      const res = await apiFetch(`/api/users/me/bookmarks/${repoId}`, {
        method: wasFavorited ? 'DELETE' : 'POST',
      });

      if (!res.ok) {
        setFavoritedIds((prev) => {
          const next = new Set(prev);
          if (wasFavorited) {
            next.add(repoId);
          } else {
            next.delete(repoId);
          }
          return next;
        });
      }
    },
    [favoritedIds]
  );

  return {
    isFavorited: (repoId: number) => favoritedIds.has(repoId),
    toggle,
    loaded,
  };
}
