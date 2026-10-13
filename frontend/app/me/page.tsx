'use client';

import { useState } from 'react';
import Link from 'next/link';
import { StackInput } from '@/components/stack-input';
import { apiFetch, apiRequest, jsonBody } from '@/lib/api-client';
import { useAuth } from '@/lib/auth-context';
import { useApiData } from '@/lib/use-api-data';
import { useRequireAuth } from '@/lib/use-require-auth';
import type { Skill } from '@/lib/types';

export default function MyPage() {
  const allowed = useRequireAuth();
  const { user } = useAuth();
  const { data: skills, loading, reload } = useApiData<Skill[]>(allowed ? '/api/users/me/skills' : null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const add = async (name: string) => {
    setSaving(true);
    setError(null);
    const res = await apiRequest<Skill>('/api/users/me/skills', { method: 'POST', ...jsonBody({ name }) });
    setSaving(false);
    if (!res.ok) setError(res.message);
    reload();
  };

  const remove = async (name: string) => {
    const skill = skills?.find((s) => s.topicName === name);
    if (!skill) return;
    setSaving(true);
    await apiFetch(`/api/users/me/skills/${skill.topicId}`, { method: 'DELETE' }).catch(() => null);
    setSaving(false);
    reload();
  };

  const names = (skills ?? []).map((s) => s.topicName);

  return (
    <main>
      <div className="page-header">
        <h1>내 스택</h1>
        <p className="page-subtitle">
          관심 기술을 저장해 두면 AI 추천에서 입력 없이 &ldquo;내 스택으로 추천&rdquo;을 쓸 수 있습니다.
        </p>
      </div>

      {user && (
        <div className="card profile-card">
          {user.profileImageUrl && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={user.profileImageUrl} alt="" className="avatar avatar--lg" />
          )}
          <div>
            <strong>{user.username}</strong>
            {user.email && <p className="muted">{user.email}</p>}
          </div>
        </div>
      )}

      <section className="card stack-editor">
        <h2 className="section-title">관심 스택</h2>
        {loading && !skills ? (
          <div className="skeleton" />
        ) : (
          <StackInput
            values={names}
            onAdd={add}
            onRemove={remove}
            placeholder="예: Java (Enter로 추가)"
            disabled={saving}
          />
        )}
        {error && <p className="alert-error">{error}</p>}
        <p className="muted">등록한 순서대로 추천 질의가 만들어집니다. 대소문자만 다른 이름은 같은 스택으로 봅니다.</p>
        {names.length > 0 && (
          <Link href="/recommend" className="button">
            이 스택으로 추천받으러 가기 →
          </Link>
        )}
      </section>
    </main>
  );
}
