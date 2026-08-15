-- ══════════════════════════════════════════════════════════════
-- V4__add_trend_score_analysis_text.sql
-- 왜: Day 26+27 통합 구현(TrendAnalysisService)에서 LLM이 생성한
--     "왜 이 레포가 뜨는가" 분석 텍스트를 영구 저장해야 하는데,
--     TrendScore(V1)는 score(숫자)/calculatedAt만 있고 텍스트를 담을
--     컬럼이 없다. score와 별개 개념(score=랭킹 정렬용 숫자,
--     analysis_text=LLM 설명 텍스트)이라 컬럼을 추가한다.
-- 왜 nullable인가: 기존 TrendScore 행(있다면)에는 분석 텍스트가 없고,
--     TrendAnalysisService가 처음 호출될 때 비로소 채워지는 구조라
--     NOT NULL 제약을 걸면 기존 행 마이그레이션이 막힌다.
-- ══════════════════════════════════════════════════════════════

ALTER TABLE trend_scores
    ADD COLUMN analysis_text TEXT;
