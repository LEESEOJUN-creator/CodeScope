// LLM 응답 텍스트 표시용. Claude는 강조를 마크다운 **굵게**로 쓰는데, 일반 텍스트로
// 찍으면 별표가 그대로 보인다. 마크다운 전체를 렌더링하는 라이브러리 대신 이 한 가지
// 문법만 <strong>으로 바꾼다 — HTML 문자열을 주입(dangerouslySetInnerHTML)하지 않고
// React 요소로 만들기 때문에 응답에 태그가 섞여 와도 실행되지 않는다(XSS 안전).
// 줄바꿈은 .prose의 white-space: pre-wrap이 그대로 살린다.
export function RichText({ text }: { text: string }) {
  const parts = text.split(/(\*\*[^*\n]+\*\*)/g);
  return (
    <p className="prose">
      {parts.map((part, i) =>
        part.startsWith('**') && part.endsWith('**') && part.length > 4 ? (
          <strong key={i}>{part.slice(2, -2)}</strong>
        ) : (
          part
        )
      )}
    </p>
  );
}
