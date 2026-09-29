package com.example.metermonitor.ocr;

import com.example.metermonitor.common.DomainTypes.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class OcrDtos {
    private OcrDtos() {}
    public record QueuedResponse(UUID requestId, ProcessingStatus status) {}
    public record ConfirmRequest(@NotNull @DecimalMin("0.0") BigDecimal value) {}
    public record StatusResponse(UUID requestId, ProcessingStatus status, BigDecimal recognizedValue,
                                 BigDecimal ocrConfidence, List<String> validationErrors,
                                 UUID meterId, String serialNumber, MeterType meterType, UUID readingId,
                                 Instant submittedAt, Instant processedAt) {
        static StatusResponse from(OcrRequest r) {
            List<String> errors = r.getValidationErrors() == null || r.getValidationErrors().isBlank() ? List.of() : List.of(r.getValidationErrors().split("\\n"));
            return new StatusResponse(r.getId(), r.getProcessingStatus(), r.getRecognizedValue(), r.getOcrConfidence(), errors,
                    r.getMeter().getId(), r.getMeter().getSerialNumber(), r.getMeter().getMeterType(), r.getReadingId(), r.getSubmittedAt(), r.getProcessedAt());
        }
    }
}
