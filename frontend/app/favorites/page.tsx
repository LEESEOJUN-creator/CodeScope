'use client';

import Link from 'next/link';
import { RepoCard, RepoCardSkeleton } from '@/components/repo-card';
import { apiFetch } from '@/lib/api-client';
import { formatDate } from '@/lib/format';
import { useApiData } from '@/lib/use-api-data';
import { useRequireAuth } from '@/lib/use-require-auth';
import type { Favorite } from '@/lib/types';

export default function FavoritesPage() {
  const allowed = useRequireAuth();
  const { data: favorites, loading, error, reload } = useApiData<Favorite[]>(
    allowed ? '/api/users/me/bookmarks' : null
  );

  const remove = async (repoId: number) => {
    await apiFetch(`/api/users/me/bookmarks/${repoId}`, { method: 'DELETE' }).catch(() => null);
    reload();
  };

  return (
    <main>
      <div className="page-header">
        <h1>즐겨찾기</h1>
        <p className="page-subtitle">별을 눌러 저장한 레포. 최근에 저장한 순서입니다.</p>
      </div>

      {error && <p className="alert-error">{error}</p>}
      <div className="card-grid">
        {(!allowed || loading) && <RepoCardSkeleton count={3} />}
        {favorites?.map((f) => (
          <div key={f.repoId} className="favorite-item">
            <RepoCard
              id={f.repoId}
              fullName={f.fullName}
              description={f.description}
              language={f.language}
              starCount={f.starCount}
              favorited
              onToggleFavorite={() => remove(f.repoId)}
            />
            <span className="favorite-item__date muted">{formatDate(f.favoritedAt)} 저장</span>
          </div>
        ))}
      </div>

      {favorites && favorites.length === 0 && (
        <div className="state-box">
          아직 즐겨찾기한 레포가 없습니다. <Link href="/trending" className="link-more">트렌드</Link>나{' '}
          <Link href="/explore" className="link-more">탐색</Link>에서 ☆를 눌러 저장해 보세요.
        </div>
      )}
    </main>
  );
}
