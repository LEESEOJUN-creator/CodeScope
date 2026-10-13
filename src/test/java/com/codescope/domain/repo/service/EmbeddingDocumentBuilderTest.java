package com.codescope.domain.repo.service;

import com.codescope.domain.repo.entity.GithubRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingDocumentBuilderTest {

    private final EmbeddingDocumentBuilder builder = new EmbeddingDocumentBuilder(2000);

    @Test
    @DisplayName("배지·이미지·HTML·URL·코드블록·마크다운 기호를 걷어내고 링크는 텍스트만 남긴다")
    void clean_removesMarkdownNoise() {
        String readme = """
                <!-- hidden comment -->
                <p align="center"><img src="https://x/logo.png" width="200"></p>
                [![Build](https://img.shields.io/badge/build-passing.svg)](https://ci.example.com)
                ![logo](https://x/logo.png)
                # yt-dlp
                A **feature-rich** command-line [audio/video downloader](https://github.com/yt-dlp/yt-dlp).
                ```bash
                pip install yt-dlp
                ```
                See https://docs.example.com for details.
                [docs]: https://docs.example.com
                | col | col |
                """;

        String cleaned = builder.clean(readme);

        assertThat(cleaned).isEqualTo(
                "yt-dlp A feature-rich command-line audio/video downloader. See for details. col col");
    }

    @Test
    @DisplayName("이름·설명·언어를 앞에 두고 README 본문을 붙인다")
    void build_prependsMetadata() {
        String document = builder.build(repo("yt-dlp/yt-dlp", "A feature-rich command-line audio/video downloader", "Python"),
                "# yt-dlp\nDownload videos from thousands of sites.");

        assertThat(document).isEqualTo("""
                yt-dlp/yt-dlp
                A feature-rich command-line audio/video downloader
                Language: Python

                yt-dlp Download videos from thousands of sites.""");
    }

    @Test
    @DisplayName("README가 비어 있어도 메타데이터만으로 문서를 만든다")
    void build_withoutReadme_usesMetadataOnly() {
        assertThat(builder.build(repo("owner/repo", "Short description", null), "  "))
                .isEqualTo("owner/repo\nShort description");
    }

    @Test
    @DisplayName("최대 길이를 넘으면 단어 경계에서 앞부분만 남긴다")
    void build_truncatesAtWordBoundary() {
        EmbeddingDocumentBuilder small = new EmbeddingDocumentBuilder(40);
        String readme = "alpha beta gamma delta epsilon zeta eta theta iota kappa";

        String document = small.build(repo("o/r", null, null), readme);

        assertThat(document).hasSizeLessThanOrEqualTo(40)
                .isEqualTo("o/r\n\nalpha beta gamma delta epsilon zeta")
                .doesNotEndWith(" ");
    }

    private static GithubRepository repo(String fullName, String description, String language) {
        return GithubRepository.builder()
                .name(fullName.split("/")[1])
                .fullName(fullName)
                .description(description)
                .language(language)
                .starCount(1)
                .forkCount(0)
                .openIssueCount(0)
                .githubUrl("https://github.com/" + fullName)
                .build();
    }
}
