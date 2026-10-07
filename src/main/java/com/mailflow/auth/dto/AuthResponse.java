package com.mailflow.auth.dto;

import java.util.List;

public record AuthResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long expiresIn,
    UserInfo user
) {
    public AuthResponse(String accessToken, String refreshToken, long expiresIn, UserInfo user) {
        this(accessToken, refreshToken, "Bearer", expiresIn, user);
    }

    public record UserInfo(
        Long id,
        String email,
        String firstName,
        String lastName,
        List<String> roles
    ) {}
}
