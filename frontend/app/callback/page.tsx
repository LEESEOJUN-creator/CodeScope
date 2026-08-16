'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { refreshAccessToken } from '@/lib/api-client';
import { useAuth } from '@/lib/auth-context';

// GithubOAuth2LoginSuccessHandler가 Refresh Token만 HttpOnly 쿠키로 세팅한 채
// 이 페이지로 리다이렉트한다(Access Token은 URL/바디 어디에도 없음). 이 페이지가
// 할 일은 그 쿠키로 POST /api/auth/refresh를 호출해 Access Token을 받아
// 메모리(AuthContext)에 저장하는 것뿐이다.
export default function CallbackPage() {
  const router = useRouter();
  const { setAccessToken } = useAuth();
  const [failed, setFailed] = useState(false);
  const startedRef = useRef(false);

  useEffect(() => {
    // React 18+ 개발 모드(StrictMode)는 effect를 의도적으로 2번 실행한다.
    // 이 호출은 Refresh Token을 회전(rotate)시키는 부수효과가 있어 중복 호출되면
    // 두 번째 호출이 이미 회전된 옛 토큰으로 401을 받을 수 있다 — ref로 막는다.
    if (startedRef.current) return;
    startedRef.current = true;

    refreshAccessToken().then((token) => {
      if (token) {
        setAccessToken(token);
        router.replace('/trending');
      } else {
        setFailed(true);
        router.replace('/login');
      }
    });
  }, [router, setAccessToken]);

  return (
    <main>
      <div className="login-screen">
        <div className="state-box" style={{ maxWidth: 320 }}>
          {failed ? '로그인 처리에 실패했습니다. 다시 시도해주세요.' : '로그인 처리 중...'}
        </div>
      </div>
    </main>
  );
}
