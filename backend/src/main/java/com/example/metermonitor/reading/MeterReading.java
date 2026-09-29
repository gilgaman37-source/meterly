package com.example.metermonitor.reading;

import com.example.metermonitor.common.DomainTypes.*;
import com.example.metermonitor.meter.Meter;
import com.example.metermonitor.user.Account;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "meter_readings")
@Getter @Setter @NoArgsConstructor
public class MeterReading {
    @Id @UuidGenerator private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false)
    private Account user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "meter_id", nullable = false)
    private Meter meter;
    @Column(nullable = false, precision = 19, scale = 6) private BigDecimal value;
    @Column(name = "submitted_at", nullable = false) private Instant submittedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ReadingSource source;
    @Enumerated(EnumType.STRING) @Column(name = "processing_status", nullable = false, length = 30) private ProcessingStatus processingStatus;
    @Column(name = "ocr_confidence", precision = 5, scale = 4) private BigDecimal ocrConfidence;
    @Column(name = "reset_event", nullable = false) private boolean resetEvent;
    @Column(name = "reset_reason", length = 300) private String resetReason;
    @Column(name = "corrected_at") private Instant correctedAt;
    @Column(name = "original_value", precision = 19, scale = 6) private BigDecimal originalValue;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
}
