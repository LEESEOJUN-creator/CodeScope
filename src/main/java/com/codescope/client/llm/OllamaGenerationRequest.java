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
//
// 왜 1.15가 아니라 1.3으로 되돌렸는가(2026-08-16, 같은 날 두 번째 조정):
//   1.3 → 1.15로 낮추는 실험을 했으나(1회 샘플로는 다국어 삽입이 5곳→1곳
//   으로 줄어 더 나아 보였음), 재현 검증 1회를 더 돌리자 1.15에서도
//   반복 생성 열화(문장이 "저는 다음 3개의 레포가 사용자 기술"에서
//   그대로 잘림)가 재현됨을 확인. 즉 1.15는 반복 루프를 안정적으로
//   막지 못한다 — LLM 생성이 확률적이라 설정당 표본 1~2개로는 결론이
//   쉽게 뒤집힌다는 것 자체가 이번에 실측으로 확인된 교훈.
//   지금까지 실측 성공/실패 횟수: 1.3은 2회 시도 모두 반복 루프 없이
//   완결(수동 curl 1회 + 실제 통합 테스트 1회), 1.15는 2회 중 1회
//   실패. "반복 루프로 인한 응답 전체 손상"이 "가끔 섞이는 외국어 단어"
//   보다 명백히 더 나쁜 실패 모드이므로, 표본이 적어도 더 안전한
//   쪽(1.3)을 유지하기로 결정.
//   다국어 삽입(1.3에서도 완전히 사라지지 않음)과 반복 열화 둘 다
//   근본적으로는 이 3B급 로컬 모델의 한계이며, repeat_penalty 튜닝만으로
//   완전히 해결되지 않는다 — 근본 해결(상위 모델 교체, num_predict
//   상한 + done:false 감지 시 재시도 등)은 다음 세션 과제로 남김.
//   docs/troubleshooting.md Day 26+27 참고.
public record OllamaGenerationRequest(String model, String prompt, boolean stream, Map<String, Object> options) {

    public static OllamaGenerationRequest of(String model, String prompt) {
        return new OllamaGenerationRequest(model, prompt, false, Map.of("repeat_penalty", 1.3));
    }
}
