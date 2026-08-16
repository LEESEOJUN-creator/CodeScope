import type { Metadata } from 'next';
import localFont from 'next/font/local';
import { AuthProvider } from '@/lib/auth-context';
import { Header } from '@/components/header';
import './globals.css';

// 구글 폰트가 아니라 로컬 파일로 로드한다(요청 사항) — npm의 pretendard
// 패키지가 배포하는 가변 폰트(woff2) 파일을 next/font/local로 직접 참조한다.
// 가변 폰트라 weight를 범위(45~920, 이 폰트의 실제 축 범위)로 지정하면
// 굵기별 파일을 따로 안 받아도 font-weight CSS만으로 굵기가 바뀐다.
const pretendard = localFont({
  src: '../node_modules/pretendard/dist/web/variable/woff2/PretendardVariable.woff2',
  variable: '--font-pretendard',
  weight: '45 920',
  display: 'swap',
});

export const metadata: Metadata = {
  title: 'CodeScope',
  description: 'GitHub 트렌드 분석 + AI 레포 추천 (Day 30~31 최소 시연)',
};

// RootLayout 자체는 Server Component로 유지하고, Access Token을 들고 있어야
// 하는 AuthProvider만 Client Component로 감싼다(Next.js 공식 패턴 —
// "Context providers"는 children을 그대로 통과시키는 얇은 Client Component로
// 분리해야 나머지 트리는 계속 서버에서 렌더링될 수 있다).
export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko" className={pretendard.variable}>
      <body>
        <AuthProvider>
          <Header />
          {children}
        </AuthProvider>
      </body>
    </html>
  );
}
