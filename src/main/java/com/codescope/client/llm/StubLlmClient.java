package com.codescope.client.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.Semaphore;

/**
 * 부하테스트 전용 LlmClient. 실제 API를 부르지 않고 고정 지연 후 고정
 * 텍스트를 돌려준다(llm.generation.provider=stub).
 *
 * 왜 필요한가: 추천/트렌드 분석 경로를 k6로 수백~수천 번 때리면 Claude API
 *   비용이 그대로 나간다. 그런데 부하테스트에서 보고 싶은 건 모델 품질이
 *   아니라 "LLM 호출이 수 초씩 걸릴 때 가상 스레드·세마포어·커넥션 풀·GC가
 *   어떻게 버티는가"다. 그 관찰에 필요한 건 응답 내용이 아니라 지연 모양이라,
 *   지연만 재현하면 충분하다.
 *
 * 왜 실제 Claude와 같은 claudeSemaphore를 거치는가: 세마포어 대기열이
 *   차오르는 모습(배압)까지 실제와 동일하게 재현해야 실험 결과를 실제 경로에
 *   대입할 수 있다.
 *
 * 왜 Thread.sleep인가: 가상 스레드에서 sleep은 캐리어 스레드를 반납하고
 *   언마운트된다 — 네트워크 I/O 대기와 같은 동작이라 실제 API 호출의 대기를
 *   흉내 내기에 적합하다.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "llm.generation.provider", havingValue = "stub")
public class StubLlmClient implements LlmClient {

    private final Semaphore claudeSemaphore;
    private final long latencyMs;

    public StubLlmClient(
            @Qualifier("claudeSemaphore") Semaphore claudeSemaphore,
            @Value("${llm.stub.generation-latency-ms}") long latencyMs
    ) {
        this.claudeSemaphore = claudeSemaphore;
        this.latencyMs = latencyMs;
        log.warn("StubLlmClient 활성화 — 실제 LLM을 호출하지 않습니다(부하테스트 전용). latencyMs={}", latencyMs);
    }

    @Override
    public String generate(String prompt) {
        try {
            claudeSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Claude 세마포어 획득 중 인터럽트됨", e);
        }

        try {
            Thread.sleep(latencyMs);
            return "[stub] 부하테스트용 고정 응답입니다. promptLength=" + prompt.length();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("stub 생성 대기 중 인터럽트됨", e);
        } finally {
            claudeSemaphore.release();
        }
    }
}
