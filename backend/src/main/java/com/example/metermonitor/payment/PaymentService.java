package com.example.metermonitor.payment;

import com.example.metermonitor.common.DomainTypes.ProcessingStatus;
import com.example.metermonitor.meter.*;
import com.example.metermonitor.reading.*;
import com.example.metermonitor.tariff.*;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class PaymentService {
    private final MeterService meters; private final MeterReadingRepository readings; private final TariffRepository tariffs;

    @Transactional(readOnly=true)
    public List<PaymentPeriod> calculate(UUID userId, UUID meterId) {
        Meter meter = meters.owned(meterId, userId);
        List<MeterReading> values = readings.findByMeterIdAndProcessingStatusOrderBySubmittedAtAsc(meterId, ProcessingStatus.COMPLETED);
        List<PaymentPeriod> result = new ArrayList<>();
        MeterReading previous = null;
        for (MeterReading current : values) {
            if (previous == null || current.isResetEvent()) {
                result.add(new PaymentPeriod(current.getId(), current.getSubmittedAt(), null, null, "RUB", current.isResetEvent() ? "RESET_BASELINE" : "MISSING_PREVIOUS"));
                previous = current; continue;
            }
            BigDecimal consumption = current.getValue().subtract(previous.getValue());
            if (consumption.signum() < 0) { result.add(new PaymentPeriod(current.getId(), current.getSubmittedAt(), consumption, null, "RUB", "INVALID_INCREASE")); previous = current; continue; }
            LocalDate start = previous.getSubmittedAt().atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate end = current.getSubmittedAt().atZone(ZoneOffset.UTC).toLocalDate();
            List<Tariff> applicable = tariffs.findOverlapping(meter.getMeterType(), meter.getRegion(), start, end);
            long totalDays = Math.max(1, ChronoUnit.DAYS.between(start, end) + 1);
            BigDecimal dailyConsumption = consumption.divide(BigDecimal.valueOf(totalDays), 12, RoundingMode.HALF_UP);
            BigDecimal payment = BigDecimal.ZERO;
            String currency = null;
            boolean tariffMissing = false;
            for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
                final LocalDate billingDay = day;
                Tariff tariff = applicable.stream()
                        .filter(t -> !t.getValidFrom().isAfter(billingDay)
                                && (t.getValidTo() == null || !t.getValidTo().isBefore(billingDay)))
                        .max(Comparator.comparing(Tariff::getValidFrom))
                        .orElse(null);
                BigDecimal rate = tariff == null ? null : tariff.effectivePricePerUnit();
                if (rate == null || rate.signum() <= 0) {
                    tariffMissing = true;
                    break;
                }
                if (currency == null) currency = tariff.getCurrency();
                if (!currency.equals(tariff.getCurrency())) {
                    tariffMissing = true;
                    break;
                }
                payment = payment.add(dailyConsumption.multiply(rate));
            }
            if (tariffMissing) result.add(new PaymentPeriod(current.getId(), current.getSubmittedAt(), consumption, null, "RUB", "MISSING_TARIFF"));
            else result.add(new PaymentPeriod(current.getId(), current.getSubmittedAt(), consumption, payment.setScale(2, RoundingMode.HALF_UP), currency, "CALCULATED"));
            previous = current;
        }
        return result;
    }

    public record PaymentPeriod(UUID readingId, Instant submittedAt, BigDecimal consumption, BigDecimal payment, String currency, String status) {}
}
