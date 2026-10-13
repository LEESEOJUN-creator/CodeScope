'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { apiRequest } from '@/lib/api-client';
import { useApiData } from '@/lib/use-api-data';
import { RichText } from '@/components/rich-text';
import type { Ecosystem, IssueRecommendation, Keyword } from '@/lib/types';

export function DiscoveryExplorer({ kind }: { kind: 'issues' | 'ecosystem' }) {
  const params = useSearchParams();
  const isIssues = kind === 'issues';
  const [query, setQuery] = useState(params.get(isIssues ? 'skills' : 'tech') ?? '');
  const [issues, setIssues] = useState<IssueRecommendation | null>(null);
  const [ecosystem, setEcosystem] = useState<Ecosystem | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { data: keywords } = useApiData<Keyword[]>('/api/keywords');

  async function submit() {
    setLoading(true);
    setError(null);
    setIssues(null);
    setEcosystem(null);
    try {
      if (isIssues) {
        const result = await apiRequest<IssueRecommendation>(`/api/issues/recommend?skills=${encodeURIComponent(query)}`);
        if (result.ok) setIssues(result.data);
        else setError(result.message);
      } else {
        const result = await apiRequest<Ecosystem>(`/api/ecosystem?tech=${encodeURIComponent(query)}`);
        if (result.ok) setEcosystem(result.data);
        else setError(result.message);
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <main>
      <div className="page-header">
        <h1>{isIssues ? '기여 이슈 찾기' : '기술 생태계'}</h1>
        <p className="page-subtitle">
          {isIssues
            ? '스택과 일치하는 수집 저장소에서 열린 good first issue를 조회하고, 첫 기여를 준비할 순서를 안내합니다.'
            : '수집된 저장소에서 어떤 언어와 토픽이 함께 쓰이는지 확인하세요.'}
        </p>
      </div>
      <form className="recommend-form" onSubmit={(event) => { event.preventDefault(); void submit(); }}>
        <input className="text-input" value={query} onChange={(event) => setQuery(event.target.value)}
          list="discovery-keywords" aria-label={isIssues ? '기여할 기술 스택' : '조회할 기술'}
          placeholder={isIssues ? 'Java, Spring Boot' : 'kafka'} required maxLength={300} disabled={loading} />
        <datalist id="discovery-keywords">
          {(keywords ?? []).map((keyword) => <option key={keyword.name} value={keyword.name} />)}
        </datalist>
        <button className="button" disabled={loading || !query.trim()}>{loading ? '조회 중…' : '조회하기'}</button>
      </form>
      {loading && <p className="state-box" role="status">{isIssues ? 'GitHub 이슈와 추천 근거를 확인하고 있어요.' : '수집 데이터를 집계하고 있어요.'}</p>}
      {error && <p className="alert-error" role="alert">{error}</p>}
      {issues && (
        <section className="ai-section">
          <div className="card"><RichText text={issues.recommendation} /></div>
          <p className="muted">조회한 저장소: {issues.searchedRepositories.join(', ') || '없음'} · 확인 시각: {new Date(issues.checkedAt).toLocaleString()}</p>
          <div className="card-grid">
            {issues.issues.map((issue) => (
              <a key={issue.id} href={issue.url} target="_blank" rel="noopener noreferrer" className="card card--link">
                <strong>#{issue.number} {issue.title}</strong>
                <p className="muted">{issue.url.replace('https://github.com/', '')}</p>
                <span className="link-more">GitHub에서 현재 상태 확인 →</span>
              </a>
            ))}
          </div>
        </section>
      )}
      {ecosystem && (
        <section>
          <h2>{ecosystem.technology} · 수집 저장소 {ecosystem.repositoryCount}개</h2>
          <p className="muted">{ecosystem.scope}</p>
          {ecosystem.repositoryCount === 0 && <p className="state-box">현재 수집 데이터에 일치하는 언어·토픽이 없습니다. 기술명과 수집 범위를 확인해주세요.</p>}
          <div className="quick-stacks">
            {ecosystem.related.map((item) => (
              <button className="chip chip--link" key={item.name} onClick={() => setQuery(item.name)}>
                {item.name} · {item.repositoryCount}
              </button>
            ))}
          </div>
          <div className="card-grid">
            {ecosystem.repositories.map((repo) => (
              <Link key={repo.id} href={`/repos/${repo.id}`} className="card card--link">
                <strong>{repo.fullName}</strong>
                <p className="muted">{repo.language ?? '주언어 미상'} · ★ {repo.starCount.toLocaleString()}</p>
              </Link>
            ))}
          </div>
        </section>
      )}
    </main>
  );
}
