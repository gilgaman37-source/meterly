package com.example.metermonitor.auth;

import com.example.metermonitor.common.DomainTypes.Role;
import jakarta.validation.constraints.*;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {}
    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 80) String username,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 128) String password) {}
    public record LoginRequest(@NotBlank String login, @NotBlank String password) {}
    public record AuthResponse(String token, UUID userId, String username, String email, Role role) {}
}
