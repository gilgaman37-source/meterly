package com.example.metermonitor.user;

import com.example.metermonitor.common.DomainTypes.Role;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor
public class Account {
    @Id @UuidGenerator
    private UUID id;
    @Column(nullable = false, unique = true, length = 80)
    private String username;
    @Column(nullable = false, unique = true, length = 254)
    private String email;
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private Role role = Role.USER;
    @Column(nullable = false)
    private boolean enabled = true;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
