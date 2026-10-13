'use client';

import { Suspense, useState } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { RepoCard, RepoCardSkeleton } from '@/components/repo-card';
import { useApiData } from '@/lib/use-api-data';
import { useFavorites } from '@/lib/use-favorites';
import type { GithubRepository, Page } from '@/lib/types';

const PAGE_SIZE = 12;

// useSearchParams는 Suspense 경계 안에서만 써야 정적 렌더링에서 빠지지 않는다(Next.js 규칙)
export default function ExplorePage() {
  return (
    <Suspense fallback={<main><div className="state-box">불러오는 중...</div></main>}>
      <Explore />
    </Suspense>
  );
}

function Explore() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const { isFavorited, toggle } = useFavorites();

  // 검색 조건은 URL 쿼리에 둔다 — 새로고침·뒤로가기·링크 공유가 그대로 동작한다
  const keyword = params.get('keyword') ?? '';
  const language = params.get('language') ?? '';
  const minStars = params.get('minStars') ?? '';
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0);

  const query = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
  if (keyword) query.set('keyword', keyword);
  if (language) query.set('language', language);
  if (minStars) query.set('minStars', minStars);

  const { data, loading, error } = useApiData<Page<GithubRepository>>(`/api/repos/search?${query}`);
  // 언어 필터 선택지는 전체 레포에서 실제로 나오는 언어로 만든다
  const { data: all } = useApiData<GithubRepository[]>('/api/repos');
  const languages = Array.from(new Set((all ?? []).map((r) => r.language).filter(Boolean) as string[])).sort();

  const update = (next: Record<string, string>) => {
    const merged = new URLSearchParams(params.toString());
    Object.entries(next).forEach(([k, v]) => (v ? merged.set(k, v) : merged.delete(k)));
    if (!('page' in next)) merged.delete('page');
    router.replace(`${pathname}?${merged}`, { scroll: false });
  };

  return (
    <main>
      <div className="page-header">
        <h1>탐색</h1>
        <p className="page-subtitle">수집된 레포를 키워드·언어·스타 수로 찾아봅니다.</p>
      </div>

      <FilterBar
        key={`${keyword}|${minStars}`}
        keyword={keyword}
        language={language}
        minStars={minStars}
        languages={languages}
        onChange={update}
      />

      {error && <p className="alert-error">{error}</p>}
      {data && (
        <p className="muted result-count">
          {data.totalElements.toLocaleString()}개 레포
          {data.totalPages > 1 && ` · ${data.number + 1} / ${data.totalPages} 페이지`}
        </p>
      )}

      <div className="card-grid">
        {loading && !data && <RepoCardSkeleton count={6} />}
        {data?.content.map((repo) => (
          <RepoCard
            key={repo.id}
            {...repo}
            favorited={isFavorited(repo.id)}
            onToggleFavorite={() => toggle(repo.id)}
          />
        ))}
      </div>

      {data && data.content.length === 0 && (
        <div className="state-box">조건에 맞는 레포가 없습니다. 필터를 줄여 보세요.</div>
      )}

      {data && data.totalPages > 1 && (
        <nav className="pagination" aria-label="페이지">
          <button
            type="button"
            className="button button--ghost button--sm"
            disabled={data.first}
            onClick={() => update({ page: String(page - 1) })}
          >
            ← 이전
          </button>
          <span className="muted">
            {data.number + 1} / {data.totalPages}
          </span>
          <button
            type="button"
            className="button button--ghost button--sm"
            disabled={data.last}
            onClick={() => update({ page: String(page + 1) })}
          >
            다음 →
          </button>
        </nav>
      )}
    </main>
  );
}

type FilterBarProps = {
  keyword: string;
  language: string;
  minStars: string;
  languages: string[];
  onChange: (next: Record<string, string>) => void;
};

// 입력 중에는 로컬 상태만 바꾸고, 제출(Enter/검색) 시 URL에 반영해 요청한다.
// URL 값이 바뀌면(뒤로가기 등) key가 바뀌어 로컬 상태가 새 값으로 다시 초기화된다.
function FilterBar({ keyword, language, minStars, languages, onChange }: FilterBarProps) {
  const [draftKeyword, setDraftKeyword] = useState(keyword);
  const [draftMinStars, setDraftMinStars] = useState(minStars);

  return (
    <form
      className="filter-bar"
      onSubmit={(e) => {
        e.preventDefault();
        onChange({ keyword: draftKeyword.trim(), minStars: draftMinStars });
      }}
    >
      <input
        className="text-input"
        value={draftKeyword}
        onChange={(e) => setDraftKeyword(e.target.value)}
        placeholder="키워드 (이름·설명)"
        aria-label="키워드"
      />
      <select
        className="text-input"
        value={language}
        onChange={(e) => onChange({ language: e.target.value })}
        aria-label="언어"
      >
        <option value="">모든 언어</option>
        {languages.map((lang) => (
          <option key={lang} value={lang}>
            {lang}
          </option>
        ))}
      </select>
      <input
        className="text-input"
        type="number"
        min={0}
        step={1000}
        value={draftMinStars}
        onChange={(e) => setDraftMinStars(e.target.value)}
        placeholder="최소 스타"
        aria-label="최소 스타 수"
      />
      <button type="submit" className="button">
        검색
      </button>
    </form>
  );
}
