'use client';

import { Suspense } from 'react';
import { useSearchParams } from 'next/navigation';
import { API_BASE_URL } from '@/lib/api-client';
import { GithubIcon } from '@/components/github-icon';

// GithubOAuth2LoginSuccessHandler가 email 정보를 못 가져온 경우
// /login?error=email_missing 으로 리다이렉트한다(백엔드 참고). 에러 종류가
// 늘어나면 이 매핑에 항목만 추가하면 된다.
const ERROR_MESSAGES: Record<string, string> = {
  email_missing:
    'GitHub 계정에 이메일이 공개 설정되어 있어야 로그인할 수 있습니다. GitHub 설정에서 이메일 공개 범위를 확인해주세요.',
};

function LoginError() {
  const searchParams = useSearchParams();
  const error = searchParams.get('error');
  if (!error) return null;
  return <p className="alert-error">{ERROR_MESSAGES[error] ?? '로그인 중 문제가 발생했습니다.'}</p>;
}

export default function LoginPage() {
  return (
    <main>
      <div className="login-screen">
        <div className="login-card">
          <h1>
            Code<span className="brand__dot">Scope</span>
          </h1>
          <p className="page-subtitle">GitHub 트렌드 분석 + AI 레포 추천</p>

          {/* useSearchParams는 Suspense 경계 없이 쓰면 페이지 전체가 정적 렌더링에서
              빠지므로, 이 값을 쓰는 부분만 별도 컴포넌트로 분리해 감쌌다(Next.js 권장 패턴). */}
          <Suspense fallback={null}>
            <LoginError />
          </Suspense>

          {/* fetch가 아니라 실제 페이지 이동이어야 한다 — GitHub 로그인 화면으로
              가는 전체 리다이렉트 체인(302 연쇄)의 시작점이기 때문 */}
          <a className="button" href={`${API_BASE_URL}/oauth2/authorization/github`}>
            <GithubIcon />
            GitHub으로 로그인
          </a>
        </div>
      </div>
    </main>
  );
}
