package com.glucoselog.auth;

public record TokenPair(String accessToken, String refreshToken, String tokenType, long expiresIn) {

    public static TokenPair of(String accessToken, String refreshToken, long expiresIn) {
        return new TokenPair(accessToken, refreshToken, "Bearer", expiresIn);
    }
}
