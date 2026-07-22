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

        if (condition.keyword() != null) {
            builder.and(repo.name.containsIgnoreCase(condition.keyword()));
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
