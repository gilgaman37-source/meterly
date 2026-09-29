package com.example.metermonitor.tariff;

import com.example.metermonitor.common.DomainTypes.MeterType;
import com.example.metermonitor.common.DomainTypes.TariffBillingMethod;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "tariffs")
@Getter @Setter @NoArgsConstructor
public class Tariff {
    @Id @UuidGenerator private UUID id;
    @Enumerated(EnumType.STRING) @Column(name = "meter_type", nullable = false, length = 30) private MeterType meterType;
    @Column(name = "price_per_unit", nullable = false, precision = 19, scale = 6) private BigDecimal pricePerUnit;
    @Enumerated(EnumType.STRING) @Column(name = "billing_method", nullable = false, length = 40) private TariffBillingMethod billingMethod = TariffBillingMethod.SINGLE_RATE;
    @Column(name = "water_component_per_m3", precision = 19, scale = 6) private BigDecimal waterComponentPerM3;
    @Column(name = "heat_component_per_gcal", precision = 19, scale = 6) private BigDecimal heatComponentPerGcal;
    @Column(name = "heat_norm_gcal_per_m3", precision = 19, scale = 9) private BigDecimal heatNormGcalPerM3;
    @Column(nullable = false, length = 3) private String currency = "RUB";
    @Column(name = "valid_from", nullable = false) private LocalDate validFrom;
    @Column(name = "valid_to") private LocalDate validTo;
    @Column(nullable = false, length = 120) private String region;
    @Column(name = "source_url", length = 500) private String sourceUrl;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    public BigDecimal effectivePricePerUnit() {
        if (billingMethod == TariffBillingMethod.TWO_COMPONENT_HOT_WATER) {
            if (waterComponentPerM3 == null || heatComponentPerGcal == null || heatNormGcalPerM3 == null) return null;
            return waterComponentPerM3.add(heatComponentPerGcal.multiply(heatNormGcalPerM3));
        }
        return pricePerUnit;
    }
}
