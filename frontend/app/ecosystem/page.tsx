import { Suspense } from 'react';
import { DiscoveryExplorer } from '@/components/discovery-explorer';

export default function EcosystemPage() {
  return <Suspense fallback={<main>불러오는 중…</main>}><DiscoveryExplorer kind="ecosystem" /></Suspense>;
}
