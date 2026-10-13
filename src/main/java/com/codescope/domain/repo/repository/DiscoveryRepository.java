package com.codescope.domain.repo.repository;

import com.codescope.domain.repo.dto.RepositoryEvidence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class DiscoveryRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public record Keyword(String name, long repositoryCount, String source) {}

    // 사용자 관심 스택만 저장된 topics 행은 제외: 실제 저장소와 연결된 키워드만 집계.
    public List<Keyword> keywords() {
        return jdbc.query("""
                WITH keywords AS (
                    SELECT lower(language) AS name, github_repository_id AS repo_id, 'language' AS source
                    FROM github_repository WHERE language IS NOT NULL AND language <> ''
                    UNION ALL
                    SELECT lower(t.name), rt.repo_id, 'github-topic'
                    FROM topics t JOIN repo_topic rt ON rt.topic_id=t.topic_id
                )
                SELECT name, count(DISTINCT repo_id) AS count, string_agg(DISTINCT source, ',') AS source
                FROM keywords GROUP BY name ORDER BY count DESC, name LIMIT 200
                """, (rs, row) -> new Keyword(rs.getString("name"), rs.getLong("count"), rs.getString("source")));
    }

    public List<RepositoryEvidence> search(String vector, List<String> terms, int limit) {
        return jdbc.query("""
                SELECT gr.*, coalesce(tags.names, '') AS topic_names,
                       coalesce(matches.names, '') AS matched_names
                FROM github_repository gr
                JOIN repo_embeddings re ON re.repo_id=gr.github_repository_id
                LEFT JOIN LATERAL (
                    SELECT string_agg(t.name, ',' ORDER BY t.name) AS names
                    FROM repo_topic rt JOIN topics t ON t.topic_id=rt.topic_id
                    WHERE rt.repo_id=gr.github_repository_id
                ) tags ON true
                LEFT JOIN LATERAL (
                    SELECT count(*) AS count, string_agg(term, ',' ORDER BY term) AS names
                    FROM jsonb_array_elements_text(CAST(? AS jsonb)) AS input(term)
                    WHERE lower(gr.language)=term
                       OR EXISTS (SELECT 1 FROM repo_topic rt JOIN topics t ON t.topic_id=rt.topic_id
                                  WHERE rt.repo_id=gr.github_repository_id AND lower(t.name)=replace(term, ' ', '-'))
                       OR position(' ' || replace(term, '-', ' ') || ' ' IN
                          ' ' || regexp_replace(lower(coalesce(gr.full_name,'') || ' ' ||
                              coalesce(gr.description,'')), '[^[:alnum:]#+.]+', ' ', 'g') || ' ') > 0
                ) matches ON true
                WHERE gr.process_status='EMBEDDED'
                ORDER BY matches.count DESC, re.embedding <=> CAST(? AS vector), gr.github_repository_id
                LIMIT ?
                """, (rs, row) -> new RepositoryEvidence(rs.getLong("github_repository_id"),
                rs.getString("full_name"), rs.getString("description"), rs.getString("language"),
                rs.getInt("star_count"), rs.getString("readme_excerpt"),
                split(rs.getString("topic_names")), split(rs.getString("matched_names"))),
                encode(terms), vector, limit);
    }

    public record RelatedTechnology(String name, long repositoryCount) {}
    public record Ecosystem(String technology, long repositoryCount, List<RelatedTechnology> related,
                            List<Map<String, Object>> repositories, String scope) {}

    public Ecosystem ecosystem(String technology) {
        String filter = """
                (lower(gr.language)=? OR EXISTS (
                    SELECT 1 FROM repo_topic rt JOIN topics t ON rt.topic_id=t.topic_id
                    WHERE rt.repo_id=gr.github_repository_id AND lower(t.name)=replace(?, ' ', '-')))
                """;
        Long count = jdbc.queryForObject("SELECT count(*) FROM github_repository gr WHERE " + filter,
                Long.class, technology, technology);
        var repos = jdbc.queryForList("""
                SELECT gr.github_repository_id AS id, gr.full_name AS "fullName",
                       gr.language, gr.star_count AS "starCount"
                FROM github_repository gr WHERE
                """ + filter + " ORDER BY gr.star_count DESC, gr.github_repository_id LIMIT 20", technology, technology);
        var related = jdbc.query("""
                WITH selected AS (SELECT gr.github_repository_id AS id, lower(gr.language) AS language
                    FROM github_repository gr WHERE
                """ + filter + """
                ), keywords AS (
                    SELECT language AS name, id FROM selected WHERE language IS NOT NULL
                    UNION
                    SELECT lower(t.name), s.id FROM selected s JOIN repo_topic rt ON rt.repo_id=s.id
                    JOIN topics t ON t.topic_id=rt.topic_id
                )
                SELECT name, count(DISTINCT id) AS count FROM keywords
                WHERE name <> ? GROUP BY name ORDER BY count DESC, name LIMIT 20
                """, (rs, row) -> new RelatedTechnology(rs.getString("name"), rs.getLong("count")),
                technology, technology, technology);
        return new Ecosystem(technology, count == null ? 0 : count, related, repos,
                "현재 수집된 저장소의 언어·GitHub topics 동시 등장 집계입니다. 전체 GitHub 점유율이나 시간별 성장률이 아닙니다.");
    }

    private String encode(List<String> terms) {
        try { return json.writeValueAsString(terms); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("키워드를 처리할 수 없습니다.", e); }
    }
    private static List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(","));
    }
}

