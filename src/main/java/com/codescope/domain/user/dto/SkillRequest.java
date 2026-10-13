package com.codescope.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// POST /api/users/me/skills 요청. 사용자는 토픽 id를 모르므로 이름으로 받는다.
// 50자 제한은 topics.name VARCHAR(50)과 맞춘 것.
public record SkillRequest(
        @NotBlank(message = "스택 이름을 입력해주세요.")
        @Size(max = 50, message = "스택 이름은 50자 이하로 입력해주세요.")
        String name
) {
}
