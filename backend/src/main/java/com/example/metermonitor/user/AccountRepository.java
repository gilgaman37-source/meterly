package com.example.metermonitor.user;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    Optional<Account> findByUsernameIgnoreCaseOrEmailIgnoreCase(String username, String email);
    boolean existsByUsernameIgnoreCaseOrEmailIgnoreCase(String username, String email);
    long countByEnabledTrue();
}
