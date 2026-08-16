'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useApiData } from '@/lib/use-api-data';
import { useFavorites } from '@/lib/use-favorites';
import { useElapsedSeconds } from '@/lib/use-elapsed-seconds';
import { StarButton } from '@/components/star-button';
import type { GithubRepository, TrendAnalysis } from '@/lib/types';

// Client Component 페이지에서는 params Promise를 use()로 풀지 않고
// useParams() 훅으로 바로 읽을 수 있다(Next.js 공식 대안 패턴).
export default function RepoDetailPage() {
  const params = useParams<{ id: string }>();
  const repoId = params.id;

  const {
    data: repo,
    error: repoError,
    loading: repoLoading,
  } = useApiData<GithubRepository>(`/api/repos/${repoId}`);

  const {
    data: analysis,
    error: analysisError,
    loading: analysisLoading,
  } = useApiData<TrendAnalysis>(`/api/trends/analysis?repoId=${repoId}`);

  const { isFavorited, toggle } = useFavorites();
  const analysisElapsed = useElapsedSeconds(analysisLoading);

  return (
    <main>
      <Link href="/trending" className="back-link">
        ← 목록으로
      </Link>

      {repoLoading && <div className="state-box">불러오는 중...</div>}
      {repoError && <p className="alert-error">{repoError}</p>}
      {repo && (
        <div className="page-header">
          <div className="card__row">
            <h1>{repo.fullName}</h1>
            <StarButton favorited={isFavorited(repo.id)} onToggle={() => toggle(repo.id)} />
          </div>
          <p className="page-subtitle">{repo.description}</p>
          <div className="card__meta" style={{ marginTop: 14 }}>
            <span>
              <strong>★ {repo.starCount.toLocaleString()}</strong>
            </span>
            <span>fork {repo.forkCount.toLocaleString()}</span>
            <span>{repo.language ?? '언어 미상'}</span>
            <span>이슈 {repo.openIssueCount.toLocaleString()}건</span>
          </div>
          <p style={{ marginTop: 14 }}>
            <a className="muted" href={repo.githubUrl} target="_blank" rel="noreferrer">
              GitHub에서 보기 →
            </a>
          </p>
        </div>
      )}

      <h2 className="section-title">왜 뜨는가 (AI 분석)</h2>
      {analysisLoading && (
        <div className="state-box">
          분석 중... {analysisElapsed}초 경과 (LLM 생성이라 1~3분 정도 걸립니다, 멈춘 게 아닙니다)
        </div>
      )}
      {analysisError && <p className="alert-error">{analysisError}</p>}
      {analysis && (
        <div className="card">
          <p className="prose">{analysis.analysis}</p>
        </div>
      )}
    </main>
  );
}
