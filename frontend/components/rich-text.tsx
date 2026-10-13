import { Fragment, type ReactNode } from 'react';

// HTML은 주입하지 않는다. AI 응답의 제목·목록·강조·코드만 React 요소로 표시한다.
function inline(text: string): ReactNode[] {
  return text.split(/(\*\*[^*\n]+\*\*|`[^`\n]+`)/g).map((part, index) => {
    if (part.startsWith('**') && part.endsWith('**')) return <strong key={index}>{part.slice(2, -2)}</strong>;
    if (part.startsWith('`') && part.endsWith('`')) return <code key={index}>{part.slice(1, -1)}</code>;
    return <Fragment key={index}>{part}</Fragment>;
  });
}

export function RichText({ text }: { text: string }) {
  const lines = text.replace(/\r\n/g, '\n').split('\n');
  const blocks: ReactNode[] = [];
  let index = 0;
  while (index < lines.length) {
    const line = lines[index].trim();
    const key = index;
    if (!line) { index++; continue; }
    if (/^#{1,6}\s/.test(line)) {
      blocks.push(<h3 key={key}>{inline(line.replace(/^#{1,6}\s+/, ''))}</h3>);
      index++;
      continue;
    }
    if (/^(`{3}|~{3})/.test(line)) {
      const fence = line.slice(0, 3);
      const code: string[] = [];
      index++;
      while (index < lines.length && !lines[index].trim().startsWith(fence)) code.push(lines[index++]);
      index++;
      blocks.push(<pre key={key}><code>{code.join('\n')}</code></pre>);
      continue;
    }
    const ordered = /^\d+[.)]\s+/.test(line);
    if (ordered || /^[-*+]\s+/.test(line)) {
      const items: ReactNode[] = [];
      const pattern = ordered ? /^\d+[.)]\s+/ : /^[-*+]\s+/;
      while (index < lines.length && pattern.test(lines[index].trim())) {
        items.push(<li key={index}>{inline(lines[index].trim().replace(pattern, ''))}</li>);
        index++;
      }
      blocks.push(ordered ? <ol key={key}>{items}</ol> : <ul key={key}>{items}</ul>);
      continue;
    }
    const paragraph: string[] = [];
    while (index < lines.length && lines[index].trim()
      && !/^(#{1,6}\s|[-*+]\s|\d+[.)]\s|`{3}|~{3})/.test(lines[index].trim())) {
      paragraph.push(lines[index++]);
    }
    blocks.push(<p key={key}>{inline(paragraph.join('\n'))}</p>);
  }
  return <div className="prose">{blocks}</div>;
}
