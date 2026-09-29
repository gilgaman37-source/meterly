package com.example.metermonitor.reading;

import com.example.metermonitor.common.DomainTypes.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class ReadingDtos {
    private ReadingDtos() {}
    public record ManualReadingRequest(@NotNull @DecimalMin("0.0") BigDecimal value, Instant submittedAt,
                                       boolean meterReset, @Size(max=300) String resetReason) {}
    public record ReadingResponse(UUID id, UUID meterId, MeterType meterType, BigDecimal value, Instant submittedAt,
                                  ReadingSource source, ProcessingStatus processingStatus, BigDecimal ocrConfidence,
                                  boolean meterReset, String resetReason, Instant correctedAt) {
        public static ReadingResponse from(MeterReading r) {
            return new ReadingResponse(r.getId(), r.getMeter().getId(), r.getMeter().getMeterType(), r.getValue(), r.getSubmittedAt(),
                    r.getSource(), r.getProcessingStatus(), r.getOcrConfidence(), r.isResetEvent(), r.getResetReason(), r.getCorrectedAt());
        }
    }
}
