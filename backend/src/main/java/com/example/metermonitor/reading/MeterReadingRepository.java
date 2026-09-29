package com.example.metermonitor.reading;

import com.example.metermonitor.common.DomainTypes.ProcessingStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface MeterReadingRepository extends JpaRepository<MeterReading, UUID>, JpaSpecificationExecutor<MeterReading> {
    Optional<MeterReading> findTopByMeterIdAndProcessingStatusOrderBySubmittedAtDesc(UUID meterId, ProcessingStatus status);
    List<MeterReading> findByMeterIdAndProcessingStatusOrderBySubmittedAtAsc(UUID meterId, ProcessingStatus status);
    boolean existsByMeterIdAndValueAndSubmittedAt(UUID meterId, BigDecimal value, Instant submittedAt);
}
