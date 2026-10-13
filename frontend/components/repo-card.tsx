'use client';

import Link from 'next/link';
import { StarButton } from '@/components/star-button';
import { compactNumber, languageColor } from '@/lib/format';

type RepoCardProps = {
  id: number;
  fullName: string;
  description: string | null;
  language: string | null;
  starCount: number;
  forkCount?: number;
  rank?: number;
  favorited?: boolean;
  onToggleFavorite?: () => void;
};

export function LanguageBadge({ language }: { language: string | null }) {
  return (
    <span className="lang">
      <span className="lang__dot" style={{ background: languageColor(language) }} aria-hidden />
      {language ?? '언어 미상'}
    </span>
  );
}

// 목록·추천 후보·즐겨찾기에서 공통으로 쓰는 레포 카드. 카드 전체가 상세로 가는 링크이고,
// 별(즐겨찾기) 버튼은 링크 이동을 막고 토글만 한다(StarButton 참고).
export function RepoCard({
  id,
  fullName,
  description,
  language,
  starCount,
  forkCount,
  rank,
  favorited,
  onToggleFavorite,
}: RepoCardProps) {
  const [owner, name] = fullName.split('/');

  return (
    <Link href={`/repos/${id}`} className="card card--link repo-card">
      <div className="card__row">
        {rank !== undefined && <span className="rank">{rank}</span>}
        <div className="repo-card__title">
          <span className="repo-card__owner">{owner}/</span>
          <span className="repo-card__name">{name}</span>
        </div>
        {onToggleFavorite && <StarButton favorited={!!favorited} onToggle={onToggleFavorite} size="sm" />}
      </div>
      <p className="card__desc">{description || '설명이 없습니다.'}</p>
      <div className="card__meta">
        <LanguageBadge language={language} />
        <span title={`스타 ${starCount.toLocaleString()}개`}>★ {compactNumber(starCount)}</span>
        {forkCount !== undefined && <span title={`포크 ${forkCount.toLocaleString()}개`}>⑂ {compactNumber(forkCount)}</span>}
      </div>
    </Link>
  );
}

export function RepoCardSkeleton({ count = 6 }: { count?: number }) {
  return (
    <>
      {Array.from({ length: count }, (_, i) => (
        <div key={i} className="card skeleton-card" aria-hidden>
          <span className="skeleton skeleton--title" />
          <span className="skeleton" />
          <span className="skeleton skeleton--short" />
        </div>
      ))}
    </>
  );
}
