package com.example.metermonitor.security;

import com.example.metermonitor.common.ApiException;
import com.example.metermonitor.user.Account;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentAccount {
    public Account get() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof Account account) return account;
        throw new ApiException(HttpStatus.UNAUTHORIZED, "Требуется авторизация");
    }
}
