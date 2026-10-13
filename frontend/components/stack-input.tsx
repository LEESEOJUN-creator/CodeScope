'use client';

import { useId, useState } from 'react';
import { useApiData } from '@/lib/use-api-data';
import type { Keyword } from '@/lib/types';

type StackInputProps = {
  values: string[];
  onAdd: (value: string) => void;
  onRemove: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
};

// 기술 스택 칩 입력. Enter 또는 쉼표로 추가, 칩의 ×로 삭제, 빈 입력에서 Backspace로 마지막 삭제.
export function StackInput({ values, onAdd, onRemove, placeholder, disabled }: StackInputProps) {
  const [draft, setDraft] = useState('');
  const suggestionsId = useId();
  const { data: keywords } = useApiData<Keyword[]>('/api/keywords');

  const commit = () => {
    const value = draft.trim().replace(/,$/, '').trim();
    if (value) onAdd(value);
    setDraft('');
  };

  return (
    <div className={`chip-input${disabled ? ' chip-input--disabled' : ''}`}>
      {values.map((value) => (
        <span key={value} className="chip">
          {value}
          <button
            type="button"
            className="chip__remove"
            aria-label={`${value} 삭제`}
            onClick={() => onRemove(value)}
            disabled={disabled}
          >
            ×
          </button>
        </span>
      ))}
      <input
        className="chip-input__field"
        value={draft}
        list={suggestionsId}
        disabled={disabled}
        placeholder={values.length === 0 ? placeholder : '스택 추가'}
        aria-label="기술 스택 입력"
        onChange={(e) => {
          const next = e.target.value;
          if (next.endsWith(',')) {
            const value = next.slice(0, -1).trim();
            if (value) onAdd(value);
            setDraft('');
          } else {
            setDraft(next);
          }
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            commit();
          } else if (e.key === 'Backspace' && draft === '' && values.length > 0) {
            onRemove(values[values.length - 1]);
          }
        }}
        onBlur={commit}
      />
      <datalist id={suggestionsId}>
        {(keywords ?? []).map((keyword) => (
          <option key={keyword.name} value={keyword.name}>{keyword.repositoryCount}개 저장소</option>
        ))}
      </datalist>
    </div>
  );
}
