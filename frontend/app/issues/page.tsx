import { Suspense } from 'react';
import { DiscoveryExplorer } from '@/components/discovery-explorer';

export default function IssuesPage() {
  return <Suspense fallback={<main>불러오는 중…</main>}><DiscoveryExplorer kind="issues" /></Suspense>;
}
