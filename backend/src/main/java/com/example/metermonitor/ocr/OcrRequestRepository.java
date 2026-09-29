package com.example.metermonitor.ocr;

import com.example.metermonitor.common.DomainTypes.ProcessingStatus;
import java.util.*;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OcrRequestRepository extends JpaRepository<OcrRequest, UUID> {
    Optional<OcrRequest> findByIdAndUserId(UUID id, UUID userId);
    List<OcrRequest> findByProcessingStatusInOrderByCreatedAtAsc(Collection<ProcessingStatus> statuses);
    List<OcrRequest> findByProcessingStatusInAndProcessedAtBeforeAndImageDeletedAtIsNull(Collection<ProcessingStatus> statuses, Instant cutoff);
}
