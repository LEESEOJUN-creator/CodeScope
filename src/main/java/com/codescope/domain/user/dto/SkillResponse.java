package com.codescope.domain.user.dto;

import com.codescope.domain.user.entity.UserSkill;

public record SkillResponse(
        Long topicId,
        String topicName
) {
    public static SkillResponse from(UserSkill skill) {
        return new SkillResponse(skill.getTopic().getId(), skill.getTopic().getName());
    }
}
