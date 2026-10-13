package com.codescope.domain.user.dto;

import com.codescope.domain.user.entity.User;

// GET /api/users/me 응답. 프론트 헤더의 프로필(아바타·이름) 표시용.
public record UserResponse(
        Long userId,
        String username,
        String email,
        String profileImageUrl
) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getUserId(), user.getUsername(), user.getEmail(), user.getProfileImageUrl());
    }
}
