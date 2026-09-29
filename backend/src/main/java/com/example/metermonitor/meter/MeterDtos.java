package com.example.metermonitor.meter;

import com.example.metermonitor.common.DomainTypes.MeterType;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class MeterDtos {
    private MeterDtos() {}
    public record CreateMeterRequest(@NotBlank @Size(max=100) String serialNumber, @NotNull MeterType meterType,
                                     @Size(max=300) String description, @Size(max=120) String region) {}
    public record UpdateMeterRequest(@NotBlank @Size(max=100) String serialNumber, @NotNull MeterType meterType,
                                     @Size(max=300) String description, @Size(max=120) String region, boolean active) {}
    public record MeterResponse(UUID id, String serialNumber, MeterType meterType, String description,
                                String region, boolean active, Instant createdAt) {
        public static MeterResponse from(Meter m) {
            return new MeterResponse(m.getId(), m.getSerialNumber(), m.getMeterType(), m.getDescription(), m.getRegion(), m.isActive(), m.getCreatedAt());
        }
    }
}
