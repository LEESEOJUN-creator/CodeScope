package com.codescope.domain.repo.repository;

import com.codescope.domain.repo.entity.Issue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// TODO: IssueRecommendService 구현 전에 필요한 것 —
//   1. Issue 엔티티 필드 보강: body(본문), url, labels(good-first-issue
//      태그) 없음. 현재는 title/state뿐이라 LLM이 판단할 근거 텍스트도,
//      사용자에게 보여줄 링크도, "good-first-issue" 필터링 수단도 없음
//   2. GitHub 이슈 수집 파이프라인 신규 구현 필요(Kafka Collect/Embed와
//      유사한 구조). 현재 issues 테이블 0건 — 수집 파이프라인 자체가
//      없음(관련 Producer/Consumer 전무)
// 이 두 가지가 선행돼야 IssueRecommendService를 실제 데이터로 검증할 수 있다.
public interface IssueJpaRepository extends JpaRepository<Issue, Long> {
    List<Issue> findByRepositoryId(Long repoId);
}
