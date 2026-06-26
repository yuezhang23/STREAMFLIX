package com.streamflix.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request/response payloads for the user API. */
public final class AuthDtos {

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 6, max = 100) String password,
            @NotBlank String displayName) {
    }

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password) {
    }

    public record AuthResponse(String token, Long userId, String email, String displayName) {
    }

    public record UserResponse(Long id, String email, String displayName) {
    }

    private AuthDtos() {
    }
}
