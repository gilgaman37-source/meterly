package com.example.metermonitor.meter;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeterRepository extends JpaRepository<Meter, UUID> {
    List<Meter> findByUserIdOrderByCreatedAtDesc(UUID userId);
    Optional<Meter> findByIdAndUserId(UUID id, UUID userId);
    boolean existsByUserIdAndSerialNumberIgnoreCase(UUID userId, String serialNumber);
    boolean existsByUserIdAndSerialNumberIgnoreCaseAndIdNot(UUID userId, String serialNumber, UUID id);
    long countByActiveTrue();
}
