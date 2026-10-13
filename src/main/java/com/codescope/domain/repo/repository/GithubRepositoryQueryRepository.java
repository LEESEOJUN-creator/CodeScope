package com.codescope.domain.repo.repository;

import com.codescope.domain.repo.dto.SearchCondition;
import com.codescope.domain.repo.entity.GithubRepository;
import com.codescope.domain.repo.entity.QGithubRepository;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class GithubRepositoryQueryRepository {

    private final JPAQueryFactory queryFactory;

    public Page<GithubRepository> search(SearchCondition condition, Pageable pageable) {
        QGithubRepository repo = QGithubRepository.githubRepository;

        BooleanBuilder builder = new BooleanBuilder();

        // 키워드는 이름 또는 설명에서 찾는다. 이름만 보면 "agent"로 검색했을 때
        // 설명이 "The open source coding agent"인 레포가 빠진다(화면 안내도 "이름·설명").
        if (condition.keyword() != null) {
            builder.and(repo.name.containsIgnoreCase(condition.keyword())
                    .or(repo.description.containsIgnoreCase(condition.keyword())));
        }
        if (condition.language() != null) {
            builder.and(repo.language.eq(condition.language()));
        }
        if (condition.topic() != null) {
            builder.and(repo.topics.any().name.eq(condition.topic()));
        }
        if (condition.minStars() != null) {
            builder.and(repo.starCount.goe(condition.minStars()));
        }

        List<GithubRepository> content = queryFactory
                .selectFrom(repo)
                .where(builder)
                .orderBy(repo.id.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(repo.count())
                .from(repo)
                .where(builder)
                .fetchOne();

        return new PageImpl<>(content, pageable, total != null ? total : 0L);
    }
}
