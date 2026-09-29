package com.example.metermonitor.ocr;

import com.example.metermonitor.common.DomainTypes.ProcessingStatus;
import com.example.metermonitor.meter.Meter;
import com.example.metermonitor.user.Account;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "ocr_requests")
@Getter @Setter @NoArgsConstructor
public class OcrRequest {
    @Id @UuidGenerator private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private Account user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "meter_id", nullable = false) private Meter meter;
    @Enumerated(EnumType.STRING) @Column(name = "processing_status", nullable = false, length = 30) private ProcessingStatus processingStatus;
    @Column(name = "storage_key", nullable = false, unique = true) private String storageKey;
    @Column(name = "original_filename", length = 255) private String originalFilename;
    @Column(name = "content_type", nullable = false, length = 100) private String contentType;
    @Column(name = "recognized_value", precision = 19, scale = 6) private BigDecimal recognizedValue;
    @Column(name = "ocr_confidence", precision = 5, scale = 4) private BigDecimal ocrConfidence;
    @Column(name = "validation_errors", columnDefinition = "text") private String validationErrors;
    @Column(name = "reading_id") private UUID readingId;
    @Column(name = "submitted_at", nullable = false) private Instant submittedAt;
    @Column(name = "processed_at") private Instant processedAt;
    @Column(name = "image_deleted_at") private Instant imageDeletedAt;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
