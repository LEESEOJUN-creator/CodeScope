package com.codescope.client.llm;

import java.util.Map;

// Ollama /api/generate 요청 바디.
// 왜 stream=false인가: 기본값(true)은 응답을 토큰 단위 JSONL 스트림으로
// 내려줘 RestClient의 단순 body() 파싱과 맞지 않는다. stream=false로
// 두면 생성이 끝난 뒤 완성된 JSON 객체 하나로 응답해 OllamaGenerationResponse와
// 그대로 매핑할 수 있다.
//
// 왜 options.repeat_penalty=1.3인가: llama3.2:3b 같은 소형 로컬 모델은 같은
//   문장을 반복하다 context 길이를 다 써버려 응답이 중간에 강제로 잘리는
//   반복 생성 열화(repetition degeneration)가 실측으로 확인됐다. 더 낮은 값
//   (1.15)은 다국어 삽입은 줄지만 반복 열화를 안정적으로 막지 못해, "응답
//   전체 손상"이 "가끔 섞이는 외국어 단어"보다 더 나쁜 실패 모드라 보고
//   1.3을 유지한다. 다국어 삽입과 반복 열화 둘 다 이 모델 크기의 근본적
//   한계라 repeat_penalty 튜닝만으로 완전히 해결되진 않는다.
//   docs/troubleshooting.md 참고.
public record OllamaGenerationRequest(String model, String prompt, boolean stream, Map<String, Object> options) {

    // 왜 options.num_predict를 추가하는가: 이 하드웨어의 CPU 추론 속도가
    //   1.3~1.9 tok/s로 실측 확정됐고, 응답이 200토큰을 넘으면 read-timeout-ms
    //   (150초)를 넘기기 쉽다. 타임아웃을 늘리는 대신 응답 길이 자체를 상한선
    //   아래로 제어한다(llm.ollama.generation.max-tokens, application.yaml).
    //   num_predict 도달 시 Ollama는 문장 중간이라도 강제 종료하므로, 프롬프트
    //   레벨에서도 "간결하게" 제약을 같이 둬(RepoRecommendService.buildPrompt)
    //   자연스럽게 그 안에서 끝나도록 유도하는 이중 안전장치로 설계했다.
    public static OllamaGenerationRequest of(String model, String prompt, int maxTokens) {
        return new OllamaGenerationRequest(model, prompt, false, Map.of(
                "repeat_penalty", 1.3,
                "num_predict", maxTokens
        ));
    }
}
