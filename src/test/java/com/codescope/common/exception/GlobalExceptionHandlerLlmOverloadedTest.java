package com.codescope.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LlmOverloadedException → 503 + Retry-After 변환 검증. 컨텍스트 전체가 필요 없어
 * standalone MockMvc로 핸들러만 붙인다.
 */
class GlobalExceptionHandlerLlmOverloadedTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new OverloadedController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("LLM 과부하는 500이 아니라 503 + Retry-After로 응답한다")
    void overloaded_returns503WithRetryAfter() throws Exception {
        mockMvc.perform(get("/overloaded"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @RestController
    static class OverloadedController {
        @GetMapping("/overloaded")
        String overloaded() {
            throw new LlmOverloadedException(5);
        }
    }
}
