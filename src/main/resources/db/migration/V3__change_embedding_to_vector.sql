-- ══════════════════════════════════════════════════════════════
-- V3__change_embedding_to_vector.sql
-- 왜: repo_embeddings.embedding_json(TEXT)은 pgvector 확장 도입 전
--     임시 컬럼이었다(V1 주석 참조). 4주차 pgvector 도입 시점에
--     실제 vector(768) 타입으로 전환한다.
-- 왜 768인가: 768 = nomic-embed-text(Ollama) 차원, 로컬/배포 동일 모델
--     사용. 비용 문제로 OpenAI 임베딩/생성 모두 배제하고 Ollama
--     단일 모델로 통일했으므로, 로컬/배포 간 모델 전환에 따른
--     차원 불일치 문제 자체가 발생하지 않는다.
-- 컬럼명 embedding_json → embedding: 데이터가 더 이상 JSON 문자열이
--     아니라 pgvector 네이티브 vector 타입이므로 "json" 접미사가
--     타입을 오도함. 엔티티 필드명도 embedding으로 맞춘다(4단계).
-- 기존 데이터: 코드베이스 전체에서 embeddingJson을 실제로 쓰는
--     Producer/Consumer가 아직 없어(RepoEmbeddingJpaRepository만 존재,
--     save 호출부 없음) 운영 데이터가 없다고 판단되지만, 안전하게
--     USING 절로 캐스팅한다(값이 있다면 JSON 배열 문자열 '[0.1, 0.2, ...]'
--     형태라고 가정하고 vector 리터럴로 변환).
-- ══════════════════════════════════════════════════════════════

CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE repo_embeddings
    ALTER COLUMN embedding_json TYPE vector(768)
    USING (embedding_json::vector(768));

ALTER TABLE repo_embeddings
    RENAME COLUMN embedding_json TO embedding;
