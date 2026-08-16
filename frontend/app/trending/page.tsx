'use client';

import Link from 'next/link';
import { useApiData } from '@/lib/use-api-data';
import { useFavorites } from '@/lib/use-favorites';
import { StarButton } from '@/components/star-button';
import type { GithubRepository } from '@/lib/types';

export default function TrendingPage() {
  const { data: repos, error, loading } = useApiData<GithubRepository[]>(
    '/api/repos/trending?limit=12'
  );
  const { isFavorited, toggle } = useFavorites();

  return (
    <main>
      <div className="page-header">
        <h1>트렌드 레포</h1>
        <p className="page-subtitle">지금 GitHub에서 뜨고 있는 오픈소스 프로젝트</p>
      </div>

      {loading && <div className="state-box">불러오는 중...</div>}
      {error && <p className="alert-error">{error}</p>}
      {repos && repos.length === 0 && (
        <div className="state-box">표시할 트렌드 레포가 없습니다.</div>
      )}

      {repos && repos.length > 0 && (
        <div className="card-grid">
          {repos.map((repo) => (
            <div className="card" key={repo.id}>
              <div className="card__row">
                <Link href={`/repos/${repo.id}`} style={{ flex: 1, minWidth: 0 }}>
                  <div className="card__title">{repo.fullName}</div>
                </Link>
                <StarButton favorited={isFavorited(repo.id)} onToggle={() => toggle(repo.id)} />
              </div>
              <Link href={`/repos/${repo.id}`}>
                <p className="card__desc">{repo.description}</p>
              </Link>
              <div className="card__meta">
                <span>
                  <strong>★ {repo.starCount.toLocaleString()}</strong>
                </span>
                <span>{repo.language ?? '언어 미상'}</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </main>
  );
}
