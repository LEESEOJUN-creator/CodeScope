'use client';

import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth-context';

const PUBLIC_NAV = [
  { href: '/explore', label: '탐색' },
  { href: '/trending', label: '트렌드' },
  { href: '/recommend', label: 'AI 추천' },
  { href: '/issues', label: '기여 이슈' },
  { href: '/ecosystem', label: '기술 생태계' },
];

const PRIVATE_NAV = [
  { href: '/favorites', label: '즐겨찾기' },
  { href: '/me', label: '내 스택' },
];

export function Header() {
  const pathname = usePathname();
  const router = useRouter();
  const { isAuthenticated, ready, user, logout } = useAuth();
  const nav = isAuthenticated ? [...PUBLIC_NAV, ...PRIVATE_NAV] : PUBLIC_NAV;

  return (
    <header className="site-header">
      <div className="site-header__inner">
        <Link href="/" className="brand">
          Code<span className="brand__dot">Scope</span>
        </Link>

        <nav className="site-nav" aria-label="주요 메뉴">
          {nav.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className={pathname.startsWith(item.href) ? 'is-active' : undefined}
              aria-current={pathname.startsWith(item.href) ? 'page' : undefined}
            >
              {item.label}
            </Link>
          ))}
        </nav>

        <div className="site-header__auth">
          {!ready ? null : isAuthenticated ? (
            <>
              <Link href="/me" className="avatar-link" title={user?.username ?? '내 정보'}>
                {user?.profileImageUrl ? (
                  // 외부(GitHub) 아바타라 next/image 도메인 설정 없이 일반 img로 표시
                  // eslint-disable-next-line @next/next/no-img-element
                  <img src={user.profileImageUrl} alt="" className="avatar" />
                ) : (
                  <span className="avatar avatar--fallback">{user?.username?.[0]?.toUpperCase() ?? '?'}</span>
                )}
                <span className="avatar-link__name">{user?.username}</span>
              </Link>
              <button
                type="button"
                className="button button--ghost button--sm"
                onClick={async () => {
                  await logout();
                  router.push('/');
                }}
              >
                로그아웃
              </button>
            </>
          ) : (
            <Link href="/login" className="button button--sm">
              로그인
            </Link>
          )}
        </div>
      </div>
    </header>
  );
}
