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
          수집된 프로젝트를 누적 스타 수로 정렬했습니다. 최근 증가율 순위는 아닙니다.
          프로젝트를 열면 README와 지표에 근거한 설명을 볼 수 있습니다.
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
