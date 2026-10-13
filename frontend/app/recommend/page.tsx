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
  const [draft, setDraft] = useState('');
  const [goal, setGoal] = useState('learn');
  const [experience, setExperience] = useState('intermediate');
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
    const queryParams = new URLSearchParams({ goal, experience });
    if (!useMySkills) queryParams.set('stack', query.join(', '));
    const path = `/api/recommend?${queryParams}`;
    const res = await apiRequest<RepoRecommend>(path);
    setLoading(false);
    if (res.ok) {
      setResult(res.data);
    } else if (res.status === 503) {
      setRetryAfter(res.retryAfter ?? 5);
    } else {
      setError(res.message);
    }
  }, [goal, experience]);

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
        <p className="eyebrow">나에게 맞는 다음 프로젝트</p>
        <h1>스택에서 시작해, 다음 단계까지.</h1>
        <p className="page-subtitle">
          기술명과 프로젝트 내용을 함께 비교해 후보를 찾고, 설명·토픽·README를 근거로
          적합한 이유와 시작 방법을 안내합니다. 입력 제안은 실제 수집된 언어와 토픽입니다.
        </p>
      </div>

      <section className="recommend-workspace card">
      <div className="recommend-options">
        <label>무엇을 하고 싶나요?
          <select className="text-input" value={goal} onChange={(e) => setGoal(e.target.value)} disabled={loading}>
            <option value="learn">기술 학습 · 실습</option>
            <option value="contribute">오픈소스 첫 기여</option>
            <option value="adopt">내 프로젝트에 도입</option>
          </select>
        </label>
        <label>이 기술에 얼마나 익숙한가요?
          <select className="text-input" value={experience} onChange={(e) => setExperience(e.target.value)} disabled={loading}>
            <option value="beginner">이제 시작하는 입문자</option>
            <option value="intermediate">사용해 본 경험이 있어요</option>
            <option value="advanced">설계와 내부 구조를 탐구해요</option>
          </select>
        </label>
      </div>
      <form
        className="recommend-form"
        onSubmit={(e) => {
          e.preventDefault();
          const query = parseStack([...stack, draft].join(','));
          if (query.length > 0) recommend(query);
        }}
      >
        <StackInput
          values={stack}
          onAdd={(v) => setStack((prev) => parseStack([...prev, v].join(',')))}
          onRemove={(v) => setStack((prev) => prev.filter((s) => s !== v))}
          placeholder="예: Java, Spring Boot, Kafka (Enter로 추가)"
          disabled={loading}
          onDraftChange={setDraft}
        />
        <button type="submit" className="button" disabled={loading || (stack.length === 0 && !draft.trim())}>
          {loading ? '추천 만드는 중…' : '맞춤 추천 받기 →'}
        </button>
      </form>
      <p className="input-help">쉼표로 기술을 구분하세요. Enter로 태그를 추가하거나 바로 추천받을 수 있습니다.</p>

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
      </section>

      {!lastQuery && <div className="recommend-preview">
        <div><span className="step-number">01</span><strong>맞는 이유</strong><p>기술 스택과 프로젝트가 어떻게 연결되는지</p></div>
        <div><span className="step-number">02</span><strong>시작하는 방법</strong><p>내 경험 수준에 맞는 첫 실습과 준비 단계</p></div>
        <div><span className="step-number">03</span><strong>확인할 근거</strong><p>실제 저장소·README와 확인이 필요한 한계</p></div>
      </div>}

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
