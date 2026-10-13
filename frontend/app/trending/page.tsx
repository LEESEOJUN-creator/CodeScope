'use client';

import { RepoCard, RepoCardSkeleton } from '@/components/repo-card';
import { useApiData } from '@/lib/use-api-data';
import { useFavorites } from '@/lib/use-favorites';
import type { GithubRepository } from '@/lib/types';

export default function TrendingPage() {
  const { data: repos, error, loading } = useApiData<GithubRepository[]>('/api/repos/trending?limit=30');
  const { isFavorited, toggle } = useFavorites();

  return (
    <main>
      <div className="page-header">
        <h1>트렌드 랭킹</h1>
        <p className="page-subtitle">
          GitHub에서 수집한 트렌딩 레포를 스타 수 기준으로 정렬했습니다(Redis Sorted Set). 레포를 열면
          &ldquo;왜 뜨는가&rdquo;를 AI가 분석합니다.
        </p>
      </div>

      {error && <p className="alert-error">{error}</p>}
      {repos && repos.length === 0 && (
        <div className="state-box">아직 랭킹이 비어 있습니다. 수집 스케줄러가 한 번 돌면 채워집니다.</div>
      )}

      <div className="card-grid">
        {loading && <RepoCardSkeleton count={9} />}
        {repos?.map((repo, i) => (
          <RepoCard
            key={repo.id}
            {...repo}
            rank={i + 1}
            favorited={isFavorited(repo.id)}
            onToggleFavorite={() => toggle(repo.id)}
          />
        ))}
      </div>
    </main>
  );
}
