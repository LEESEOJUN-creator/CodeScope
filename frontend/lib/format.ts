// 1234 → "1.2k", 1234567 → "1.2M". 카드처럼 좁은 곳에서 큰 숫자를 짧게 보여준다.
export function compactNumber(value: number): string {
  return new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 }).format(value);
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: 'short', day: 'numeric' }).format(date);
}

// GitHub linguist 색상 중 수집 데이터에 실제로 나오는 언어 위주. 없으면 회색.
const LANGUAGE_COLORS: Record<string, string> = {
  Java: '#b07219',
  Python: '#3572A5',
  TypeScript: '#3178c6',
  JavaScript: '#f1e05a',
  Go: '#00ADD8',
  Rust: '#dea584',
  C: '#555555',
  'C++': '#f34b7d',
  'C#': '#178600',
  Kotlin: '#A97BFF',
  Swift: '#F05138',
  Ruby: '#701516',
  PHP: '#4F5D95',
  Shell: '#89e051',
  HTML: '#e34c26',
  CSS: '#563d7c',
  Batchfile: '#C1F12E',
  Markdown: '#083fa1',
  Dart: '#00B4AB',
  Vue: '#41b883',
};

export function languageColor(language: string | null | undefined): string {
  return (language && LANGUAGE_COLORS[language]) || '#6b6b73';
}

// "Java, Spring Boot,Kafka" → ["Java", "Spring Boot", "Kafka"] (중복·공백 제거)
export function parseStack(input: string): string[] {
  const seen = new Set<string>();
  return input
    .split(',')
    .map((s) => s.trim())
    .filter((s) => {
      const key = s.toLowerCase();
      if (!s || seen.has(key)) return false;
      seen.add(key);
      return true;
    });
}
