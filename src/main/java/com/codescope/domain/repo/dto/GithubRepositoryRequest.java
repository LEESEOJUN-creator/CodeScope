package com.codescope.domain.repo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class GithubRepositoryRequest {

    @NotBlank(message = "레포지토리 이름은 필수입니다")
    private String name;

    @NotBlank(message = "전체 이름은 필수입니다")
    private String fullName;

    private String description;
    private String language;
    private int starCount;
    private int forkCount;
    private int openIssueCount;

    @NotBlank(message = "GitHub URL은 필수입니다")
    private String githubUrl;

    private java.util.List<String> topics = new java.util.ArrayList<>();
}
