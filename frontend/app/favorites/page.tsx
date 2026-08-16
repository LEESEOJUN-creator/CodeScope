'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { apiFetch } from '@/lib/api-client';
import type { ApiResponse, Favorite } from '@/lib/types';

// 이 페이지는 useApiData가 아니라 직접 상태를 들고 있다 — 해제(삭제) 시
// 서버를 다시 조회하지 않고 로컬 배열에서 바로 splice해서 지워야
// 매끄럽기 때문(useApiData는 read-only 조회 전용으로 설계했음, lib/use-api-data.ts 참고).
export default function FavoritesPage() {
  const router = useRouter();
  const [favorites, setFavorites] = useState<Favorite[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [removingId, setRemovingId] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      const res = await apiFetch('/api/users/me/bookmarks');
      if (cancelled) return;

      if (res.status === 401) {
        router.replace('/login');
        return;
      }

      const body: ApiResponse<Favorite[]> | null = await res.json().catch(() => null);
      if (cancelled) return;

      if (!res.ok) {
        setError(body?.message ?? '즐겨찾기 목록을 불러오지 못했습니다.');
        return;
      }

      setFavorites(body?.data ?? []);
    }

    load();

    return () => {
      cancelled = true;
    };
  }, [router]);

  async function handleRemove(repoId: number) {
    setRemovingId(repoId);
    const res = await apiFetch(`/api/users/me/bookmarks/${repoId}`, { method: 'DELETE' });
    if (res.ok) {
      setFavorites((prev) => (prev ? prev.filter((f) => f.repoId !== repoId) : prev));
    }
    setRemovingId(null);
  }

  return (
    <main>
      <div className="page-header">
        <h1>내 즐겨찾기</h1>
        <p className="page-subtitle">별표(★) 표시한 레포 모음</p>
      </div>

      {favorites === null && !error && <div className="state-box">불러오는 중...</div>}
      {error && <p className="alert-error">{error}</p>}
      {favorites && favorites.length === 0 && (
        <div className="state-box">아직 즐겨찾기한 레포가 없습니다. 트렌드 목록에서 ☆를 눌러보세요.</div>
      )}

      {favorites && favorites.length > 0 && (
        <div className="card-grid">
          {favorites.map((favorite) => (
            <div className="card" key={favorite.repoId}>
              <div className="card__row">
                <div className="card__title" style={{ flex: 1, minWidth: 0 }}>
                  {favorite.fullName}
                </div>
                <button
                  type="button"
                  className="star-button star-button--active"
                  aria-label="즐겨찾기 해제"
                  disabled={removingId === favorite.repoId}
                  onClick={() => handleRemove(favorite.repoId)}
                >
                  ★
                </button>
              </div>
              <p className="card__desc">{favorite.description}</p>
              <div className="card__meta">
                <span>
                  <strong>★ {favorite.starCount.toLocaleString()}</strong>
                </span>
                <span>{favorite.language ?? '언어 미상'}</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </main>
  );
}
