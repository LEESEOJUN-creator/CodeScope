'use client';

import { useCallback, useState } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { AiPanel } from '@/components/ai-panel';
import { RichText } from '@/components/rich-text';
import { LanguageBadge } from '@/components/repo-card';
import { StarButton } from '@/components/star-button';
import { apiRequest } from '@/lib/api-client';
import { compactNumber, formatDate } from '@/lib/format';
import { useApiData } from '@/lib/use-api-data';
import { useFavorites } from '@/lib/use-favorites';
import type { GithubRepository, TrendAnalysis } from '@/lib/types';

export default function RepoDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { data: repo, error, loading, status } = useApiData<GithubRepository>(`/api/repos/${id}`);
  const { isFavorited, toggle } = useFavorites();

  if (loading) {
    return (
      <main>
        <div className="state-box">불러오는 중...</div>
      </main>
    );
  }

  if (!repo) {
    return (
      <main>
        <Link href="/explore" className="back-link">← 탐색으로</Link>
        <p className="alert-error">{status === 404 ? '존재하지 않는 레포입니다.' : error}</p>
      </main>
    );
  }

  return (
    <main>
      <Link href="/trending" className="back-link">← 트렌드로</Link>

      <div className="detail-header">
        <div className="card__row">
          <h1 className="detail-title">{repo.fullName}</h1>
          <StarButton favorited={isFavorited(repo.id)} onToggle={() => toggle(repo.id)} />
        </div>
        <p className="page-subtitle">{repo.description || '설명이 없습니다.'}</p>
        {repo.topics.length > 0 && (
          <div className="topic-list">
            {repo.topics.map((topic) => (
              <span key={topic} className="chip chip--static">{topic}</span>
            ))}
          </div>
        )}
        <a className="button button--ghost button--sm" href={repo.githubUrl} target="_blank" rel="noreferrer">
          GitHub에서 보기 ↗
        </a>
      </div>

      <dl className="stat-grid">
        <Stat label="스타" value={compactNumber(repo.starCount)} title={repo.starCount.toLocaleString()} />
        <Stat label="포크" value={compactNumber(repo.forkCount)} title={repo.forkCount.toLocaleString()} />
        <Stat label="오픈 이슈" value={compactNumber(repo.openIssueCount)} title={repo.openIssueCount.toLocaleString()} />
        <div className="stat">
          <dt>언어</dt>
          <dd><LanguageBadge language={repo.language} /></dd>
        </div>
        <Stat label="마지막 수집" value={formatDate(repo.updatedAt)} />
      </dl>

      <TrendAnalysisSection repoId={repo.id} />
    </main>
  );
}

function Stat({ label, value, title }: { label: string; value: string; title?: string }) {
  return (
    <div className="stat" title={title}>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

// 분석은 버튼을 눌렀을 때만 요청한다 — 상세 화면을 열기만 해도 LLM 호출(비용)이
// 나가지 않게. 같은 레포 분석은 서버가 1시간 캐싱하므로 두 번째부터는 즉시 온다.
function TrendAnalysisSection({ repoId }: { repoId: number }) {
  const [analysis, setAnalysis] = useState<TrendAnalysis | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [retryAfter, setRetryAfter] = useState<number | null>(null);
  const [requested, setRequested] = useState(false);

  const analyze = useCallback(async () => {
    setRequested(true);
    setLoading(true);
    setError(null);
    setRetryAfter(null);
    const res = await apiRequest<TrendAnalysis>(`/api/trends/analysis?repoId=${repoId}`);
    setLoading(false);
    if (res.ok) {
      setAnalysis(res.data);
    } else if (res.status === 503) {
      setRetryAfter(res.retryAfter ?? 5);
    } else {
      setError(res.message);
    }
  }, [repoId]);

  return (
    <section className="ai-section">
      <div className="section-head">
        <h2 className="section-title">왜 뜨는가 · AI 분석</h2>
        {analysis && (
          <span className="muted">스타·포크·이슈 지표 기반 · 1시간 캐시</span>
        )}
      </div>

      {!requested ? (
        <div className="ai-panel ai-panel--idle">
          <div>
            <strong>이 레포가 왜 주목받는지 분석해 볼까요?</strong>
            <p className="muted">수집된 지표만 근거로 Claude가 설명을 번역하고 인기 이유를 정리합니다.</p>
          </div>
          <button type="button" className="button" onClick={analyze}>
            AI 분석 보기
          </button>
        </div>
      ) : (
        <AiPanel
          loading={loading}
          retryAfter={retryAfter}
          error={error}
          onRetry={analyze}
          loadingLabel="AI가 트렌드를 분석하고 있어요"
        >
          {analysis && (
            <div className="card ai-result">
              <RichText text={analysis.analysis} />
            </div>
          )}
        </AiPanel>
      )}
    </section>
  );
}
