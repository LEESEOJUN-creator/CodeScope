'use client';

import { useEffect, useState } from 'react';
import { useElapsedSeconds } from '@/lib/use-elapsed-seconds';

type AiPanelProps = {
  loading: boolean;
  // 503(LLM 과부하)일 때 서버가 준 Retry-After 초. null이면 과부하 아님.
  retryAfter: number | null;
  error: string | null;
  onRetry: () => void;
  loadingLabel: string;
  children?: React.ReactNode;
};

// AI(LLM) 응답 영역의 공통 상태 표시: 생성 중(경과 시간) / 과부하(카운트다운 후 재시도) /
// 오류 / 결과. 백엔드는 LLM 대기열이 상한을 넘으면 503 + Retry-After로 즉시
// 거절한다(LlmAdmissionControl) — 그 경우 "실패"가 아니라 "잠시 후 다시"로 안내한다.
export function AiPanel({ loading, retryAfter, error, onRetry, loadingLabel, children }: AiPanelProps) {
  const elapsed = useElapsedSeconds(loading);
  const remaining = useCountdown(retryAfter);

  if (loading) {
    return (
      <div className="ai-panel ai-panel--loading" role="status">
        <span className="spinner" aria-hidden />
        <div>
          <strong>{loadingLabel}</strong>
          <p className="muted">{elapsed}초 경과 · {elapsed < 20 ? '저장소 근거를 읽고 답변을 구성하고 있습니다.' : '상세 답변 생성에 시간이 걸리고 있습니다. 잠시만 기다려주세요.'}</p>
        </div>
      </div>
    );
  }

  if (retryAfter !== null) {
    return (
      <div className="ai-panel ai-panel--busy" role="status">
        <div>
          <strong>지금 AI 요청이 몰려 있어요</strong>
          <p className="muted">
            {remaining > 0 ? `${remaining}초 후에 다시 시도할 수 있습니다.` : '이제 다시 시도할 수 있습니다.'}
          </p>
        </div>
        <button type="button" className="button button--sm" disabled={remaining > 0} onClick={onRetry}>
          다시 시도
        </button>
      </div>
    );
  }

  if (error) {
    return (
      <div className="ai-panel ai-panel--error" role="alert">
        <div>
          <strong>AI 응답을 받지 못했습니다</strong>
          <p className="muted">{error}</p>
        </div>
        <button type="button" className="button button--ghost button--sm" onClick={onRetry}>
          다시 시도
        </button>
      </div>
    );
  }

  return <>{children}</>;
}

function useCountdown(seconds: number | null): number {
  const [remaining, setRemaining] = useState(0);

  useEffect(() => {
    if (seconds === null) return;
    const end = Date.now() + seconds * 1000;
    const tick = () => setRemaining(Math.max(0, Math.ceil((end - Date.now()) / 1000)));
    queueMicrotask(tick);
    const id = setInterval(tick, 250);
    return () => clearInterval(id);
  }, [seconds]);

  return seconds === null ? 0 : remaining;
}
