package com.example.metermonitor.reading;

import com.example.metermonitor.common.*;
import com.example.metermonitor.common.DomainTypes.*;
import com.example.metermonitor.config.AppProperties;
import com.example.metermonitor.meter.*;
import com.example.metermonitor.reading.ReadingDtos.*;
import com.example.metermonitor.user.Account;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class ReadingService {
    private final MeterReadingRepository readings; private final MeterService meters; private final AppProperties properties;

    @Transactional
    public ReadingResponse submit(Account user, UUID meterId, ManualReadingRequest request) {
        Meter meter = meters.owned(meterId, user.getId());
        if (!meter.isActive()) throw new ApiException(HttpStatus.CONFLICT, "Счётчик отключён");
        Instant submitted = request.submittedAt() == null ? Instant.now() : request.submittedAt();
        validate(meter, request.value(), submitted, request.meterReset(), request.resetReason());
        if (readings.existsByMeterIdAndValueAndSubmittedAt(meterId, request.value(), submitted))
            throw new ApiException(HttpStatus.CONFLICT, "Такое показание уже передано");
        MeterReading reading = new MeterReading(); reading.setUser(user); reading.setMeter(meter); reading.setValue(request.value());
        reading.setSubmittedAt(submitted); reading.setSource(ReadingSource.MANUAL); reading.setProcessingStatus(ProcessingStatus.COMPLETED);
        reading.setResetEvent(request.meterReset()); reading.setResetReason(request.resetReason());
        return ReadingResponse.from(readings.save(reading));
    }

    public void validate(Meter meter, BigDecimal value, Instant submitted, boolean reset, String resetReason) {
        List<ApiException.FieldIssue> issues = new ArrayList<>();
        if (value == null) issues.add(new ApiException.FieldIssue("value", "Укажите значение показания"));
        else if (value.signum() < 0) issues.add(new ApiException.FieldIssue("value", "Показание не может быть отрицательным"));
        else if (value.compareTo(properties.reading().maximumValue()) > 0) issues.add(new ApiException.FieldIssue("value", "Показание превышает допустимый максимум"));
        if (submitted == null || submitted.isAfter(Instant.now().plus(properties.reading().futureTolerance())))
            issues.add(new ApiException.FieldIssue("submittedAt", "Дата передачи некорректна или находится слишком далеко в будущем"));
        if (reset && (resetReason == null || resetReason.isBlank())) issues.add(new ApiException.FieldIssue("resetReason", "Укажите причину сброса или замены счётчика"));
        if (!reset && value != null) readings.findTopByMeterIdAndProcessingStatusOrderBySubmittedAtDesc(meter.getId(), ProcessingStatus.COMPLETED)
                .filter(previous -> value.compareTo(previous.getValue()) < 0)
                .ifPresent(previous -> issues.add(new ApiException.FieldIssue("value", "Показание не может быть меньше предыдущего")));
        if (!issues.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Ошибка проверки данных", issues);
    }

    @Transactional(readOnly = true)
    public Page<ReadingResponse> history(Account user, UUID meterId, Instant from, Instant to, MeterType type,
                                         ReadingSource source, ProcessingStatus status, Pageable pageable) {
        Meter meter = meters.owned(meterId, user.getId());
        if (type != null && meter.getMeterType() != type) return Page.empty(pageable);
        Specification<MeterReading> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>(); p.add(cb.equal(root.get("meter").get("id"), meterId));
            p.add(cb.equal(root.get("user").get("id"), user.getId()));
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.get("submittedAt"), from));
            if (to != null) p.add(cb.lessThanOrEqualTo(root.get("submittedAt"), to));
            if (source != null) p.add(cb.equal(root.get("source"), source));
            if (status != null) p.add(cb.equal(root.get("processingStatus"), status));
            return cb.and(p.toArray(Predicate[]::new));
        };
        return readings.findAll(spec, pageable).map(ReadingResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<ReadingResponse> userHistory(Account user, UUID meterId, Instant from, Instant to, MeterType type,
                                            ReadingSource source, ProcessingStatus status, Pageable pageable) {
        Specification<MeterReading> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>(); p.add(cb.equal(root.get("user").get("id"), user.getId()));
            if (meterId != null) p.add(cb.equal(root.get("meter").get("id"), meterId));
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.get("submittedAt"), from));
            if (to != null) p.add(cb.lessThanOrEqualTo(root.get("submittedAt"), to));
            if (type != null) p.add(cb.equal(root.get("meter").get("meterType"), type));
            if (source != null) p.add(cb.equal(root.get("source"), source));
            if (status != null) p.add(cb.equal(root.get("processingStatus"), status));
            return cb.and(p.toArray(Predicate[]::new));
        };
        return readings.findAll(spec, pageable).map(ReadingResponse::from);
    }
}
