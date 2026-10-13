'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { RepoCard, RepoCardSkeleton } from '@/components/repo-card';
import { useApiData } from '@/lib/use-api-data';
import { useFavorites } from '@/lib/use-favorites';
import type { GithubRepository } from '@/lib/types';

const QUICK_STACKS = ['Java, Spring Boot, Kafka', 'TypeScript, React, Next.js', 'Python, Machine Learning', 'Go, Kubernetes'];

export default function HomePage() {
  const router = useRouter();
  const [keyword, setKeyword] = useState('');
  const { data: trending, loading, error } = useApiData<GithubRepository[]>('/api/repos/trending?limit=6');
  const { isFavorited, toggle } = useFavorites();

  return (
    <main>
      <section className="hero">
        <p className="hero__eyebrow">GitHub 트렌드 × AI 추천</p>
        <h1 className="hero__title">
          지금 뜨는 오픈소스,
          <br />
          <span className="text-accent">내 스택</span>에 맞는 것만 골라서.
        </h1>
        <p className="hero__subtitle">
          GitHub 트렌딩 레포를 수집·임베딩해 두고, 기술 스택을 입력하면 pgvector 유사도 검색으로 후보를
          찾은 뒤 Claude가 그 안에서만 골라 이유를 설명합니다.
        </p>

        <form
          className="search-bar"
          onSubmit={(e) => {
            e.preventDefault();
            const q = keyword.trim();
            router.push(q ? `/explore?keyword=${encodeURIComponent(q)}` : '/explore');
          }}
        >
          <input
            className="text-input text-input--lg"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="레포 이름이나 설명으로 검색 (예: agent, react)"
            aria-label="레포 검색"
          />
          <button type="submit" className="button button--lg">
            검색
          </button>
        </form>

        <div className="quick-stacks">
          <span className="muted">스택으로 바로 추천받기</span>
          {QUICK_STACKS.map((stack) => (
            <Link key={stack} href={`/recommend?stack=${encodeURIComponent(stack)}`} className="chip chip--link">
              {stack}
            </Link>
          ))}
        </div>
      </section>

      <section>
        <div className="section-head">
          <h2 className="section-title">지금 뜨는 레포</h2>
          <Link href="/trending" className="link-more">
            전체 랭킹 →
          </Link>
        </div>
        {error && <p className="alert-error">{error}</p>}
        <div className="card-grid">
          {loading && <RepoCardSkeleton count={6} />}
          {trending?.map((repo, i) => (
            <RepoCard
              key={repo.id}
              {...repo}
              rank={i + 1}
              favorited={isFavorited(repo.id)}
              onToggleFavorite={() => toggle(repo.id)}
            />
          ))}
        </div>
        {trending && trending.length === 0 && (
          <div className="state-box">
            아직 트렌드 랭킹이 비어 있습니다. 수집 스케줄러가 한 번 돌면 채워집니다.
          </div>
        )}
      </section>

      <section className="feature-grid">
        <Link href="/explore" className="card card--link feature">
          <span className="feature__icon" aria-hidden>⌕</span>
          <strong>탐색</strong>
          <p className="muted">키워드·언어·스타 수로 수집된 레포를 필터링합니다.</p>
        </Link>
        <Link href="/recommend" className="card card--link feature">
          <span className="feature__icon" aria-hidden>✦</span>
          <strong>AI 추천</strong>
          <p className="muted">스택을 넣으면 유사한 레포를 찾고 Claude가 추천 이유를 설명합니다.</p>
        </Link>
        <Link href="/trending" className="card card--link feature">
          <span className="feature__icon" aria-hidden>↗</span>
          <strong>트렌드 분석</strong>
          <p className="muted">레포 상세에서 &ldquo;왜 뜨는가&rdquo;를 AI가 지표 기반으로 분석합니다.</p>
        </Link>
      </section>
    </main>
  );
}
