'use client';

import { useEffect, useState } from 'react';

// AI 분석/추천처럼 90~160초씩 걸리는 LLM 호출은 "멈춘 것처럼 보인다"는 게
// 실제로 반복해서 겪은 문제였다(로딩 텍스트만 있고 진행 표시가 없었음).
// 초 단위로 흘러가는 숫자를 보여주는 것만으로도 "느리지만 동작 중"이라는
// 신호가 된다 — 진행률을 알 수 없는 LLM 호출에 대한 최소한의 UX 장치.
export function useElapsedSeconds(active: boolean): number {
  const [seconds, setSeconds] = useState(0);

  useEffect(() => {
    if (!active) {
      return;
    }

    const start = Date.now();
    const id = setInterval(() => {
      setSeconds(Math.floor((Date.now() - start) / 1000));
    }, 1000);

    return () => clearInterval(id);
  }, [active]);

  return seconds;
}
