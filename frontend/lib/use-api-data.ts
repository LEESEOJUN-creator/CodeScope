'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { apiFetch } from './api-client';
import type { ApiResponse } from './types';

type UseApiDataResult<T> = {
  data: T | null;
  error: string | null;
  loading: boolean;
};

// trending / recommend / repo 상세 페이지가 공통으로 쓰는 "GET 호출 + 로딩/에러
// 상태 + 401 최종 실패 시 /login 리다이렉트" 패턴을 한 곳에 모았다. apiFetch가
// 이미 401 → refresh → 1회 재시도까지 처리하므로, 여기서 다시 401을 보면
// "재시도까지 실패한 것"이 확정된 상태 — 그때만 /login으로 보낸다.
export function useApiData<T>(path: string): UseApiDataResult<T> {
  const router = useRouter();
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;

    // setState 호출을 effect 본문 최상단이 아니라 비동기 함수 안(마이크로태스크
    // 이후)으로 옮겼다 — react-hooks/set-state-in-effect 규칙은 effect 본문에서
    // "즉시" setState하는 것만 문제 삼는다(연쇄 렌더링 유발 우려). 이 요청은
    // path가 바뀔 때마다 다시 로딩 상태로 되돌려야 하는 정상적인 데이터 패칭이라
    // 규칙 취지상 문제 되는 패턴은 아니다.
    async function load() {
      setLoading(true);
      setError(null);

      try {
        const res = await apiFetch(path);

        if (res.status === 401) {
          router.replace('/login');
          return;
        }

        const body: ApiResponse<T> | null = await res.json().catch(() => null);

        if (cancelled) return;

        if (!res.ok) {
          setError(body?.message ?? `요청에 실패했습니다 (HTTP ${res.status})`);
          return;
        }

        setData(body?.data ?? null);
      } catch {
        if (!cancelled) {
          setError('네트워크 오류가 발생했습니다.');
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }

    load();

    return () => {
      cancelled = true;
    };
  }, [path, router]);

  return { data, error, loading };
}
