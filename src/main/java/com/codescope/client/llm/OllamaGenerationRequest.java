package com.codescope.client.llm;

import java.util.Map;

// Ollama /api/generate 요청 바디.
// 왜 stream=false인가: 기본값(true)은 응답을 토큰 단위 JSONL 스트림으로
// 내려줘 RestClient의 단순 body() 파싱과 맞지 않는다. stream=false로
// 두면 생성이 끝난 뒤 완성된 JSON 객체 하나로 응답해 OllamaGenerationResponse와
// 그대로 매핑할 수 있다.
//
// 왜 options.repeat_penalty를 명시하는가(2026-08-16 실측 계기):
//   RepoRecommendService 실제 검증 중 llama3.2:3b가 같은 문장("test-recommend/
//   spring-kafka-toolkit" 추천 이유)을 3번 그대로 반복하다가 context
//   길이(4096 토큰)를 다 써버려 응답이 문장 중간("사용자 기술")에서
//   강제로 잘리는 것을 실측으로 확인했다("done": false로 응답 종료).
//   소형 로컬 모델에서 흔한 반복 생성 열화(repetition degeneration) 현상.
//   Ollama 기본값(1.1)보다 높은 1.3으로 반복에 대한 페널티를 강화해
//   같은 n-gram을 다시 뱉을 확률을 낮춘다.
public record OllamaGenerationRequest(String model, String prompt, boolean stream, Map<String, Object> options) {

    public static OllamaGenerationRequest of(String model, String prompt) {
        return new OllamaGenerationRequest(model, prompt, false, Map.of("repeat_penalty", 1.3));
    }
}
