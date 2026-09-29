package com.example.metermonitor.admin;

import com.example.metermonitor.admin.AdminDtos.*;
import com.example.metermonitor.security.CurrentAccount;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin") @RequiredArgsConstructor
public class AdminController {
    private final AdminService service;
    private final CurrentAccount current;

    @GetMapping("/users") List<AdminUserResponse> users() { return service.users(); }

    @PatchMapping("/users/{id}") AdminUserResponse updateUser(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return service.updateUser(id, request, current.get().getId());
    }

    @GetMapping("/tariffs") List<TariffResponse> tariffs() { return service.tariffs(); }

    @PostMapping("/tariffs") ResponseEntity<TariffResponse> createTariff(@Valid @RequestBody TariffRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createTariff(request));
    }

    @PutMapping("/tariffs/{id}") TariffResponse updateTariff(@PathVariable UUID id, @Valid @RequestBody TariffRequest request) {
        return service.updateTariff(id, request);
    }

    @DeleteMapping("/tariffs/{id}") ResponseEntity<Void> deleteTariff(@PathVariable UUID id) {
        service.deleteTariff(id);
        return ResponseEntity.noContent().build();
    }
}
