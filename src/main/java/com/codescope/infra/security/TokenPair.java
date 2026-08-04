package com.codescope.infra.security;

public record TokenPair(String accessToken, String refreshToken) {

    public static TokenPair of(String accessToken, String refreshToken) {
        return new TokenPair(accessToken, refreshToken);
    }
}
