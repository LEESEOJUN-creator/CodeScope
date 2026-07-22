-- ══════════════════════════════════════════════════════════════
-- V2__add_issues_repo_id_index.sql
-- 왜: IssueJpaRepository.findByRepositoryId()가 issues.repo_id로 조회하는데,
--     FK 제약만 있고 인덱스가 없어 Seq Scan이 됨 (Postgres는 FK를 걸어도
--     자동으로 인덱스를 만들어주지 않음). 인덱스 감사 중 발견된 공백을 보완.
-- ══════════════════════════════════════════════════════════════

CREATE INDEX idx_issues_repo_id ON issues (repo_id);
