package com.example.metermonitor.meter;

import com.example.metermonitor.common.DomainTypes.MeterType;
import com.example.metermonitor.user.Account;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "meters", uniqueConstraints = @UniqueConstraint(name = "uk_meter_user_serial", columnNames = {"user_id", "serial_number"}))
@Getter @Setter @NoArgsConstructor
public class Meter {
    @Id @UuidGenerator private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false)
    private Account user;
    @Column(name = "serial_number", nullable = false, length = 100)
    private String serialNumber;
    @Enumerated(EnumType.STRING) @Column(name = "meter_type", nullable = false, length = 30)
    private MeterType meterType;
    @Column(length = 300) private String description;
    @Column(length = 120) private String region;
    @Column(nullable = false) private boolean active = true;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
