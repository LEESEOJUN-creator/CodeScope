'use client';

type StarButtonProps = {
  favorited: boolean;
  onToggle: () => void;
  size?: 'sm' | 'md';
};

// Link 카드 안에 얹히는 경우가 많아, 클릭 시 부모 <Link>의 이동을 반드시
// 막아야 한다(stopPropagation + preventDefault) — 안 그러면 별 누른다는 게
// 상세 페이지로 이동해버린다.
export function StarButton({ favorited, onToggle, size = 'md' }: StarButtonProps) {
  return (
    <button
      type="button"
      aria-label={favorited ? '즐겨찾기 해제' : '즐겨찾기 추가'}
      aria-pressed={favorited}
      onClick={(e) => {
        e.preventDefault();
        e.stopPropagation();
        onToggle();
      }}
      className={`star-button${favorited ? ' star-button--active' : ''}${
        size === 'sm' ? ' star-button--sm' : ''
      }`}
    >
      {favorited ? '★' : '☆'}
    </button>
  );
}
