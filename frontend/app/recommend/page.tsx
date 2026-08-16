'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { apiFetch } from '@/lib/api-client';
import { useElapsedSeconds } from '@/lib/use-elapsed-seconds';
import type { ApiResponse, RepoRecommend } from '@/lib/types';

// 백엔드 우선순위(RepoRecommendService.resolveStack): stack 파라미터가 있으면
// 그걸 그대로 쓰고, 없으면 로그인 사용자의 저장된 관심 스택(UserSkill)을 쓴다.
// 지금은 관심 스택을 등록하는 화면이 없어(UserSkillController 미구현) 로그인만
// 해서는 항상 400이 난다 — 그래서 직접 입력창을 둬서 즉시 확인 가능하게 했다.
export default function RecommendPage() {
  const router = useRouter();
  const [stackInput, setStackInput] = useState('');
  const [data, setData] = useState<RepoRecommend | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const fetchRecommend = useCallback(
    async (stack?: string) => {
      setLoading(true);
      setError(null);

      const path = stack ? `/api/recommend?stack=${encodeURIComponent(stack)}` : '/api/recommend';
      const res = await apiFetch(path);

      if (res.status === 401) {
        router.replace('/login');
        return;
      }

      const body: ApiResponse<RepoRecommend> | null = await res.json().catch(() => null);

      if (!res.ok) {
        setError(body?.message ?? `요청에 실패했습니다 (HTTP ${res.status})`);
        setData(null);
        setLoading(false);
        return;
      }

      setData(body?.data ?? null);
      setLoading(false);
    },
    [router]
  );

  useEffect(() => {
    // fetchRecommend() 내부가 setState로 시작하는 비동기 함수라, effect 본문에서
    // 곧바로 호출하면 react-hooks/set-state-in-effect 규칙에 걸린다
    // (lib/use-api-data.ts와 동일한 이유). queueMicrotask로 한 틱 늦춰
    // "effect가 즉시 setState하는 패턴"이 아니게 만든다 — 동작은 동일(다음
    // 마이크로태스크에서 바로 실행)하고 사용자 입장에서 체감 차이도 없다.
    queueMicrotask(() => {
      fetchRecommend();
    });
  }, [fetchRecommend]);

  const elapsed = useElapsedSeconds(loading);

  return (
    <main>
      <Link href="/trending" className="back-link">
        ← 트렌드로
      </Link>

      <div className="page-header">
        <h1>AI 추천</h1>
        <p className="page-subtitle">
          pgvector 유사도 검색 + LLM 추천. 로그인 사용자는 저장된 관심 스택을 자동으로
          쓰지만(관심 스택 등록 화면은 아직 없음), 아래에 직접 입력해서 바로 시도할 수도 있습니다.
        </p>
      </div>

      <form
        className="stack-form"
        onSubmit={(e) => {
          e.preventDefault();
          fetchRecommend(stackInput.trim() || undefined);
        }}
      >
        <input
          className="text-input"
          value={stackInput}
          onChange={(e) => setStackInput(e.target.value)}
          placeholder="예: Java,Spring Boot,Kafka"
        />
        <button type="submit" className="button" disabled={loading}>
          추천 받기
        </button>
      </form>

      {loading && (
        <div className="state-box">
          추천 생성 중... {elapsed}초 경과 (LLM 생성이라 1~3분 정도 걸립니다, 멈춘 게 아닙니다)
        </div>
      )}
      {error && <p className="alert-error">{error}</p>}

      {data && (
        <>
          <p className="muted" style={{ marginBottom: 12 }}>
            기준 스택 · {data.stack}
          </p>
          <div className="card">
            <p className="prose">{data.recommendation}</p>
          </div>

          <h2 className="section-title">후보 레포</h2>
          <div className="card-grid">
            {data.candidates.map((candidate) => (
              <div className="card" key={candidate.id}>
                <div className="card__title">{candidate.fullName}</div>
                <p className="card__desc">{candidate.description}</p>
                <div className="card__meta">
                  <span>
                    <strong>★ {candidate.starCount.toLocaleString()}</strong>
                  </span>
                  <span>{candidate.language ?? '언어 미상'}</span>
                </div>
              </div>
            ))}
          </div>
        </>
      )}
    </main>
  );
}
