'use client';

import { useCallback, useEffect, useState } from 'react';
import { apiRequest, type ApiResult } from './api-client';

type UseApiDataResult<T> = {
  data: T | null;
  error: string | null;
  status: number | null;
  loading: boolean;
  reload: () => void;
};

// GET 조회 전용 훅. path가 null이면 요청하지 않는다(조건부 조회 — 예: 로그인 후에만).
// 401 처리(로그인 이동)는 화면마다 다르므로(공개 화면은 이동하면 안 됨) 여기서
// 하지 않고 status로 넘긴다.
export function useApiData<T>(path: string | null): UseApiDataResult<T> {
  const [result, setResult] = useState<{ path: string; response: ApiResult<T> } | null>(null);
  const [loading, setLoading] = useState(path !== null);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    if (path === null) {
      return;
    }
    let cancelled = false;
    const controller = new AbortController();

    // effect 본문에서 동기 setState를 피하려고(React 19 lint) 마이크로태스크로 시작한다
    queueMicrotask(() => {
      if (!cancelled) setLoading(true);
    });
    apiRequest<T>(path, { signal: controller.signal }).then((res) => {
      if (cancelled) return;
      setResult({ path, response: res });
      setLoading(false);
    });

    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [path, version]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);

  const current = path !== null && result?.path === path ? result.response : null;
  return {
    data: current?.ok ? current.data : null,
    error: current && !current.ok ? current.message : null,
    status: current?.status ?? null,
    loading: path !== null && (loading || result?.path !== path),
    reload,
  };
}
