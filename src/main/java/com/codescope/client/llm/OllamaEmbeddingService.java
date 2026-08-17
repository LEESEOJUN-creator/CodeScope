package com.codescope.client.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Ollama(nomic-embed-text)로 README를 임베딩하는 구현체.
 *
 * 왜 청크를 평균(mean pooling)해서 레포당 벡터 1개로 만드는가:
 *   RepoEmbedding은 GithubRepository와 1:1(UNIQUE) 구조라 청크별로 여러
 *   행을 저장할 수 없다. 청크 단위 검색이 가능하려면 스키마 자체를 1:N
 *   으로 바꿔야 하는데, 지금 목적은 "레포 단위 유사도 추천"(RAG 컨텍스트
 *   주입용 Top-N 레포 검색)이라 청크 단위 검색까지는 필요 없다. 그래서
 *   청크 임베딩들을 축(dimension)별로 평균 내 레포 전체를 대표하는
 *   벡터 1개로 축약한다.
 */
@Slf4j
@Service
public class OllamaEmbeddingService implements EmbeddingService {

    private static final int CHUNK_SIZE = 500;

    // nomic-embed-text는 "저장할 문서"와 "검색 질문"을 서로 다른 벡터
    // 공간으로 학습한 모델이라, 인덱싱(저장) 대상 텍스트 앞에 이 prefix를
    // 붙이는 것이 공식 권장 사용법이다. 붙이지 않으면 두 목적이 뒤섞인
    // 공간에 벡터가 놓여 검색 정확도가 떨어진다. (검색 질의 쪽에는
    // "search_query: "를 붙여야 하지만, 이 클래스는 저장 파이프라인
    // 전용이라 "search_document: "만 사용한다.)
    private static final String DOCUMENT_PREFIX = "search_document: ";

    // 검색 질의(embedQuery)용 prefix. DOCUMENT_PREFIX와 짝을 이루는
    // nomic-embed-text 공식 권장 사용법(질의 쪽).
    private static final String QUERY_PREFIX = "search_query: ";

    private final RestClient ollamaRestClient;
    private final Semaphore ollamaSemaphore;
    private final String embeddingModel;

    // @Value를 필드가 아닌 생성자 파라미터에 붙이기 위해 Lombok
    // @RequiredArgsConstructor 대신 생성자를 직접 쓴다(다른 두 협력
    // 객체는 그대로 생성자 주입 - @Autowired 미사용 원칙 유지).
    public OllamaEmbeddingService(
            RestClient ollamaRestClient,
            @Qualifier("ollamaSemaphore") Semaphore ollamaSemaphore,
            @Value("${llm.ollama.embedding-model}") String embeddingModel
    ) {
        this.ollamaRestClient = ollamaRestClient;
        this.ollamaSemaphore = ollamaSemaphore;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public float[] embed(String repoFullName, String readmeText) {
        if (readmeText == null || readmeText.isBlank()) {
            throw new IllegalStateException("임베딩할 README가 비어있습니다: fullName=" + repoFullName);
        }

        List<String> chunks = chunk(readmeText);
        float[] sum = null;

        for (String chunk : chunks) {
            float[] vector = callOllama(DOCUMENT_PREFIX + chunk);
            if (sum == null) {
                sum = new float[vector.length];
            }
            for (int i = 0; i < vector.length; i++) {
                sum[i] += vector[i];
            }
        }

        for (int i = 0; i < sum.length; i++) {
            sum[i] /= chunks.size();
        }

        log.info("임베딩 생성 완료: fullName={}, chunkCount={}, dim={}",
                repoFullName, chunks.size(), sum.length);

        return sum;
    }

    @Override
    public float[] embedQuery(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            throw new IllegalStateException("임베딩할 검색 질의가 비어있습니다");
        }

        // 왜 청킹하지 않는가: 이 메서드의 실제 호출 대상(사용자 스택
        // 입력 등)은 README와 달리 짧은 텍스트라 500자를 넘길 일이
        // 거의 없다. embed()처럼 청크+평균 로직을 그대로 가져오면
        // "짧은 질의 하나"에 불필요한 복잡도만 늘어난다.
        return callOllama(QUERY_PREFIX + queryText);
    }

    // 500자 단위 단순 분할(오버랩 없음). 문장 중간이 끊길 수 있으나
    // 이번 스코프에서는 감수한다.
    private List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += CHUNK_SIZE) {
            chunks.add(text.substring(i, Math.min(text.length(), i + CHUNK_SIZE)));
        }
        return chunks;
    }

    private float[] callOllama(String prompt) {
        // 왜 acquire()가 try 블록 바깥인가: DbSemaphoreConfig와 동일한 이유 —
        // try 안에서 acquire하면 acquire 자체의 실패(InterruptedException)에도
        // finally의 release()가 실행되어 permits 카운트가 실제 획득 없이 늘어난다.
        try {
            ollamaSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ollama 임베딩 세마포어 획득 중 인터럽트됨", e);
        }

        try {
            OllamaEmbeddingResponse response = ollamaRestClient.post()
                    .uri("/api/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new OllamaEmbeddingRequest(embeddingModel, prompt))
                    .retrieve()
                    .body(OllamaEmbeddingResponse.class);

            if (response == null || response.embedding() == null || response.embedding().isEmpty()) {
                throw new IllegalStateException("Ollama 임베딩 응답이 비어있습니다: model=" + embeddingModel);
            }

            List<Double> embedding = response.embedding();
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = embedding.get(i).floatValue();
            }
            return vector;
        } finally {
            ollamaSemaphore.release();
        }
    }
}
