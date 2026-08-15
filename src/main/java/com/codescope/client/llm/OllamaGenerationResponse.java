package com.codescope.client.llm;

// Ollama /api/generate 응답 바디(stream=false) - {"response": "...", "done": true, ...}
// done/model 등 다른 필드도 내려오지만 지금은 response 텍스트만 필요해
// 나머지는 매핑하지 않는다(Jackson 기본 설정은 알 수 없는 필드를 무시).
public record OllamaGenerationResponse(String response) {
}
