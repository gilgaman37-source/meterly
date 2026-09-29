package com.example.metermonitor.auth;

import com.example.metermonitor.auth.AuthDtos.*;
import com.example.metermonitor.common.ApiException;
import com.example.metermonitor.security.JwtService;
import com.example.metermonitor.user.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class AuthService {
    private final AccountRepository accounts;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase();
        if (accounts.existsByUsernameIgnoreCaseOrEmailIgnoreCase(username, email))
            throw new ApiException(HttpStatus.CONFLICT, "Имя пользователя или электронная почта уже зарегистрированы");
        Account account = new Account();
        account.setUsername(username);
        account.setEmail(email);
        account.setPasswordHash(encoder.encode(request.password()));
        return response(accounts.save(account));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        Account account = accounts.findByUsernameIgnoreCaseOrEmailIgnoreCase(request.login(), request.login())
                .filter(a -> a.isEnabled() && encoder.matches(request.password(), a.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Неверное имя пользователя, электронная почта или пароль"));
        return response(account);
    }

    private AuthResponse response(Account a) {
        return new AuthResponse(jwt.issue(a), a.getId(), a.getUsername(), a.getEmail(), a.getRole());
    }
}
