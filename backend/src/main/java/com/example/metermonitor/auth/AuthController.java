package com.example.metermonitor.auth;

import com.example.metermonitor.auth.AuthDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/auth") @RequiredArgsConstructor
public class AuthController {
    private final AuthService service;
    @PostMapping("/register") ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.register(r));
    }
    @PostMapping("/login") AuthResponse login(@Valid @RequestBody LoginRequest r) { return service.login(r); }
}
