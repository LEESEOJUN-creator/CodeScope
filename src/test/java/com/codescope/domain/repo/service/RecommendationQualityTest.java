package com.codescope.domain.repo.service;

import com.codescope.client.llm.LlmClient;
import com.codescope.domain.repo.dto.RepositoryEvidence;
import com.codescope.domain.user.service.UserSkillService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecommendationQualityTest {
    private final StackKeywords keywords = new StackKeywords();
    private final RecommendationSearchService search = mock(RecommendationSearchService.class);
    private final LlmClient llm = mock(LlmClient.class);
    private final RepoRecommendService service = new RepoRecommendService(search, llm,
            mock(UserSkillService.class), keywords, new ObjectMapper());

    @Test void normalizesOnlyKnownAliasesAndPreservesAmbiguousInput() {
        assertThat(keywords.parse("자바, Spring Boot, k8s, jev, JAVA"))
                .containsExactly("java", "spring-boot", "kubernetes", "jev");
        assertThatThrownBy(() -> keywords.parse(" , ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> keywords.parse("x".repeat(301))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void promptUsesReadmeAndDoesNotDemandFiveLines() {
        ReflectionTestUtils.setField(service, "evidenceMaxChars", 10);
        var evidence = new RepositoryEvidence(1L, "spring-projects/spring-boot", "framework", "Java",
                100, "Readme evidence that is longer", List.of("spring-boot"), List.of("java"));
        when(search.search("자바")).thenReturn(List.of(evidence));
        when(llm.generate(anyString())).thenReturn("상세 추천");
        assertThat(service.recommend("자바").recommendation()).isEqualTo("상세 추천");
        var prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(llm).generate(prompt.capture());
        assertThat(prompt.getValue()).contains("Readme evi", "spring-boot", "기여 준비", "비신뢰")
                .doesNotContain("5줄 이내", "Readme evidence that is longer", "영어, 일본어");
    }

    @Test void absenceMeansLimitedCorpusNotNonexistentTechnology() {
        when(search.search("jev")).thenReturn(List.of());
        assertThat(service.recommend("jev").recommendation()).contains("존재하지 않는다는 뜻은 아닙니다");
        verifyNoInteractions(llm);
    }

    @Test void unmatchedInputGetsExplicitNoticeEvenWhenLlmOmitsIt() {
        ReflectionTestUtils.setField(service, "evidenceMaxChars", 100);
        when(search.search("jev")).thenReturn(List.of(new RepositoryEvidence(
                1L, "owner/repo", null, "Python", 1, null, List.of(), List.of())));
        when(llm.generate(anyString())).thenReturn("의미 기반 후보");
        assertThat(service.recommend("jev").recommendation()).contains("직접 확인되지 않은 입력: jev", "수집 범위 부족");
    }
}
