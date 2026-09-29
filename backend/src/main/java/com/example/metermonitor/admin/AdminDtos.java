package com.example.metermonitor.admin;

import com.example.metermonitor.common.DomainTypes.*;
import com.example.metermonitor.tariff.Tariff;
import com.example.metermonitor.user.Account;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

public final class AdminDtos {
    private AdminDtos() {}

    public record AdminUserResponse(UUID id, String username, String email, Role role, boolean enabled, Instant createdAt) {
        static AdminUserResponse from(Account user) {
            return new AdminUserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getRole(), user.isEnabled(), user.getCreatedAt());
        }
    }

    public record UpdateUserRequest(@NotNull Role role, boolean enabled) {}

    public record TariffRequest(
            @NotNull MeterType meterType,
            @NotNull TariffBillingMethod billingMethod,
            @DecimalMin("0.0") BigDecimal pricePerUnit,
            @DecimalMin("0.0") BigDecimal waterComponentPerM3,
            @DecimalMin("0.0") BigDecimal heatComponentPerGcal,
            @DecimalMin("0.0") BigDecimal heatNormGcalPerM3,
            @NotBlank @Size(max=3) String currency,
            @NotNull LocalDate validFrom,
            LocalDate validTo,
            @NotBlank @Size(max=120) String region,
            @Size(max=500) String sourceUrl) {}

    public record TariffResponse(UUID id, MeterType meterType, TariffBillingMethod billingMethod, BigDecimal pricePerUnit,
                                 BigDecimal waterComponentPerM3, BigDecimal heatComponentPerGcal, BigDecimal heatNormGcalPerM3,
                                 BigDecimal effectivePricePerUnit, String currency, LocalDate validFrom, LocalDate validTo,
                                 String region, String sourceUrl, Instant createdAt) {
        static TariffResponse from(Tariff tariff) {
            return new TariffResponse(tariff.getId(), tariff.getMeterType(), tariff.getBillingMethod(), tariff.getPricePerUnit(),
                    tariff.getWaterComponentPerM3(), tariff.getHeatComponentPerGcal(), tariff.getHeatNormGcalPerM3(),
                    tariff.effectivePricePerUnit(), tariff.getCurrency(), tariff.getValidFrom(), tariff.getValidTo(),
                    tariff.getRegion(), tariff.getSourceUrl(), tariff.getCreatedAt());
        }
    }
}
