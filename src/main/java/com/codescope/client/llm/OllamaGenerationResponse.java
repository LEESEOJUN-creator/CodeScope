package com.codescope.client.llm;

// Ollama /api/generate 응답 바디(stream=false) - {"response": "...", "done": true, ...}
// model 등 다른 필드도 내려오지만 지금은 response/done만 필요해 나머지는
// 매핑하지 않는다(Jackson 기본 설정은 알 수 없는 필드를 무시).
//
// 왜 done을 추가로 매핑하는가: num_predict(max-tokens)에 도달해 문장 중간에
//   강제 종료된 응답도
//   response 필드 자체는 비어있지 않아 그동안은 구분 없이 정상 응답처럼
//   보였다. done=false면 "자연스럽게 끝난 게 아니라 잘렸다"는 뜻이라,
//   OllamaLlmClient가 이 신호로 잘림 여부를 로그에 남길 수 있게 한다.
public record OllamaGenerationResponse(String response, Boolean done) {
}
