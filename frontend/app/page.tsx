import { redirect } from 'next/navigation';

// 루트 경로는 화면이 아니라 진입점 — Day 30~31 스코프의 첫 화면은 /login이다.
// 서버에서 바로 리다이렉트하므로 클라이언트 JS가 로드되기 전에 이동한다.
export default function RootPage() {
  redirect('/login');
}
