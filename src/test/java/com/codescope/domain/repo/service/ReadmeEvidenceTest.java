package com.codescope.domain.repo.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReadmeEvidenceTest {
    @Test void preservesCommandsLinksAndHeadingsButRemovesBadges() {
        String evidence = ReadmeEvidence.prepare("""
                <!-- hidden -->
                [![Build](https://example.com/badge.svg)](https://example.com/ci)
                <img src="logo.svg">
                # Install
                ```sh
                npm install example
                example --config <path>
                ```
                Read the [guide](https://example.com/guide).
                """);
        assertThat(evidence).contains("# Install", "npm install example", "--config <path>",
                "[guide](https://example.com/guide)").doesNotContain("badge.svg", "logo.svg", "hidden");
    }

    @Test void includesUsageBeyondLongIntroductionWithinPromptBudget() {
        String readme = "# Example\n" + "Introduction. ".repeat(200)
                + "\n## Installation\n```sh\nnpm install example\n```\n"
                + "## Usage\nRun example with your config.\n## Sponsors\nSponsor list.";
        String excerpt = ReadmeEvidence.excerpt(ReadmeEvidence.prepare(readme), 1600);
        assertThat(excerpt).hasSizeLessThanOrEqualTo(1600).contains("Introduction", "npm install example", "Run example")
                .doesNotContain("Sponsor list");
    }

    @Test void limitsStoredEvidenceAndKeepsMissingReadmeMissing() {
        assertThat(ReadmeEvidence.prepare(null)).isNull();
        assertThat(ReadmeEvidence.prepare("<!-- only a comment -->")).isNull();
        assertThat(ReadmeEvidence.prepare("x".repeat(20_000))).hasSize(12_000);
        assertThat(ReadmeEvidence.excerpt(null, 1600)).isEmpty();
    }
}
