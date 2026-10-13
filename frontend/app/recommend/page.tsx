'use client';

import { Suspense, useCallback, useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { AiPanel } from '@/components/ai-panel';
import { RichText } from '@/components/rich-text';
import { RepoCard } from '@/components/repo-card';
import { StackInput } from '@/components/stack-input';
import { apiRequest } from '@/lib/api-client';
import { useAuth } from '@/lib/auth-context';
import { parseStack } from '@/lib/format';
import { useApiData } from '@/lib/use-api-data';
import { useFavorites } from '@/lib/use-favorites';
import type { RepoRecommend, Skill } from '@/lib/types';

export default function RecommendPage() {
  return (
    <Suspense fallback={<main><div className="state-box">불러오는 중...</div></main>}>
      <Recommend />
    </Suspense>
  );
}

function Recommend() {
  const params = useSearchParams();
  const { isAuthenticated, ready } = useAuth();
  const { isFavorited, toggle } = useFavorites();
  const { data: mySkills } = useApiData<Skill[]>(isAuthenticated ? '/api/users/me/skills' : null);

  const [stack, setStack] = useState<string[]>(() => parseStack(params.get('stack') ?? ''));
  const [result, setResult] = useState<RepoRecommend | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [retryAfter, setRetryAfter] = useState<number | null>(null);
  const [lastQuery, setLastQuery] = useState<string[] | null>(null);

  // useMySkills=true면 stack 파라미터 없이 호출 → 서버가 저장된 관심 스택을 사용
  // (RepoRecommendService.resolveStack). 직접 입력이 있으면 그게 우선이다.
  const recommend = useCallback(async (query: string[], useMySkills = false) => {
    setLastQuery(query);
    setLoading(true);
    setError(null);
    setRetryAfter(null);
    setResult(null);
    const path = useMySkills ? '/api/recommend' : `/api/recommend?stack=${encodeURIComponent(query.join(', '))}`;
    const res = await apiRequest<RepoRecommend>(path);
    setLoading(false);
    if (res.ok) {
      setResult(res.data);
    } else if (res.status === 503) {
      setRetryAfter(res.retryAfter ?? 5);
    } else {
      setError(res.message);
    }
  }, []);

  // 홈의 "스택으로 바로 추천받기"처럼 ?stack= 으로 들어오면 바로 한 번 요청한다
  const autoRan = useRef(false);
  useEffect(() => {
    if (autoRan.current || stack.length === 0 || !params.get('stack')) return;
    autoRan.current = true;
    queueMicrotask(() => recommend(stack));
  }, [params, recommend, stack]);

  const skillNames = (mySkills ?? []).map((s) => s.topicName);

  return (
    <main>
      <div className="page-header">
        <h1>AI 추천</h1>
        <p className="page-subtitle">
          기술명과 프로젝트 내용을 함께 비교해 후보를 찾고, 설명·토픽·README를 근거로
          적합한 이유와 시작 방법을 안내합니다. 입력 제안은 실제 수집된 언어와 토픽입니다.
        </p>
      </div>

      <form
        className="recommend-form"
        onSubmit={(e) => {
          e.preventDefault();
          if (stack.length > 0) recommend(stack);
        }}
      >
        <StackInput
          values={stack}
          onAdd={(v) => setStack((prev) => parseStack([...prev, v].join(',')))}
          onRemove={(v) => setStack((prev) => prev.filter((s) => s !== v))}
          placeholder="예: Java, Spring Boot, Kafka (Enter로 추가)"
          disabled={loading}
        />
        <button type="submit" className="button" disabled={loading || stack.length === 0}>
          추천 받기
        </button>
      </form>

      {ready && (
        <div className="my-stack-hint">
          {isAuthenticated ? (
            skillNames.length > 0 ? (
              <>
                <span className="muted">내 관심 스택:</span>
                {skillNames.map((name) => (
                  <span key={name} className="chip chip--static">{name}</span>
                ))}
                <button
                  type="button"
                  className="button button--ghost button--sm"
                  disabled={loading}
                  onClick={() => {
                    setStack(skillNames);
                    recommend(skillNames, true);
                  }}
                >
                  내 스택으로 추천
                </button>
                <Link href="/me" className="link-more">편집</Link>
              </>
            ) : (
              <span className="muted">
                <Link href="/me" className="link-more">내 스택</Link>에 관심 기술을 저장해 두면 한 번에 추천받을 수 있어요.
              </span>
            )
          ) : (
            <span className="muted">
              <Link href="/login" className="link-more">로그인</Link>하면 관심 스택을 저장해 두고 바로 추천받을 수 있어요.
            </span>
          )}
        </div>
      )}

      {lastQuery && (
        <section className="ai-section">
          <AiPanel
            loading={loading}
            retryAfter={retryAfter}
            error={error}
            onRetry={() => recommend(lastQuery)}
            loadingLabel="후보를 찾고 AI가 추천 이유를 쓰고 있어요"
          >
            {result && (
              <>
                <div className="card ai-result">
                  <p className="ai-result__label">✦ {result.stack} 기준 추천</p>
                  <RichText text={result.recommendation} />
                  <Link href={`/issues?skills=${encodeURIComponent(result.stack)}`} className="link-more">
                    이 스택으로 실제 기여 이슈 찾기 →
                  </Link>
                </div>

                <div className="section-head">
                  <h2 className="section-title">추천 검토 후보 {result.candidates.length}개</h2>
                  <span className="muted">기술명 일치 · 내용 유사도 기준</span>
                </div>
                <div className="card-grid">
                  {result.candidates.map((c, i) => (
                    <RepoCard
                      key={c.id}
                      {...c}
                      rank={i + 1}
                      favorited={isFavorited(c.id)}
                      onToggleFavorite={() => toggle(c.id)}
                    />
                  ))}
                </div>
              </>
            )}
          </AiPanel>
        </section>
      )}
    </main>
  );
}
