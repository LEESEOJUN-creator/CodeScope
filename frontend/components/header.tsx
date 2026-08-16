'use client';

import Link from 'next/link';
import { useAuth } from '@/lib/auth-context';

// 로그인 전(/login, /callback)엔 nav를 안 보여준다 — 아직 못 가는 화면을
// 링크로 노출하지 않는 게 맞다. isAuthenticated가 useAuth(메모리 토큰
// 존재 여부)로 이미 계산돼 있어 별도 라우트 분기 없이 조건부 렌더링만으로 충분하다.
export function Header() {
  const { isAuthenticated } = useAuth();

  return (
    <header className="site-header">
      <div className="site-header__inner">
        <Link href={isAuthenticated ? '/trending' : '/login'} className="brand">
          Code<span className="brand__dot">Scope</span>
        </Link>
        {isAuthenticated && (
          <nav className="site-nav">
            <Link href="/trending">트렌드</Link>
            <Link href="/recommend">AI 추천</Link>
            <Link href="/favorites">즐겨찾기</Link>
          </nav>
        )}
      </div>
    </header>
  );
}
