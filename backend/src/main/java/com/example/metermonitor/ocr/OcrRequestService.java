package com.example.metermonitor.ocr;

import com.example.metermonitor.common.*;
import com.example.metermonitor.common.DomainTypes.*;
import com.example.metermonitor.config.AppProperties;
import com.example.metermonitor.meter.*;
import com.example.metermonitor.ocr.OcrDtos.*;
import com.example.metermonitor.queue.OcrJobQueue;
import com.example.metermonitor.reading.*;
import com.example.metermonitor.storage.ImageStorage;
import com.example.metermonitor.user.Account;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.web.multipart.MultipartFile;

@Service @RequiredArgsConstructor
public class OcrRequestService implements ApplicationRunner {
    private final OcrRequestRepository requests; private final MeterReadingRepository readings; private final MeterService meters;
    private final ReadingService readingService; private final ImageStorage storage; private final OcrEngine engine;
    private final OcrJobQueue queue; private final AppProperties properties; private final org.springframework.context.ApplicationEventPublisher events;

    @Transactional
    public QueuedResponse upload(Account user, UUID meterId, MultipartFile file) {
        Meter meter = meters.owned(meterId, user.getId());
        if (!meter.isActive()) throw new ApiException(HttpStatus.CONFLICT, "Счётчик отключён");
        var stored = storage.store(file);
        OcrRequest r = new OcrRequest(); r.setUser(user); r.setMeter(meter); r.setStorageKey(stored.storageKey());
        r.setOriginalFilename(stored.originalFilename()); r.setContentType(stored.contentType()); r.setProcessingStatus(ProcessingStatus.QUEUED);
        r.setSubmittedAt(Instant.now()); requests.save(r); events.publishEvent(new Queued(r.getId()));
        return new QueuedResponse(r.getId(), r.getProcessingStatus());
    }

    @Transactional(readOnly=true) public StatusResponse status(Account user, UUID id) { return StatusResponse.from(owned(id, user.getId())); }

    @Transactional
    public StatusResponse confirm(Account user, UUID id, BigDecimal value) {
        OcrRequest r = owned(id, user.getId());
        if (r.getProcessingStatus() != ProcessingStatus.REQUIRES_CONFIRMATION && r.getProcessingStatus() != ProcessingStatus.FAILED)
            throw new ApiException(HttpStatus.CONFLICT, "Этот запрос OCR не ожидает исправления");
        readingService.validate(r.getMeter(), value, r.getSubmittedAt(), false, null);
        BigDecimal originallyRecognized = r.getRecognizedValue();
        MeterReading reading = createReading(r, value, r.getOcrConfidence());
        if (originallyRecognized != null && originallyRecognized.compareTo(value) != 0) {
            reading.setOriginalValue(originallyRecognized); reading.setCorrectedAt(Instant.now());
        }
        r.setReadingId(reading.getId()); r.setRecognizedValue(value); r.setValidationErrors(null);
        r.setProcessingStatus(ProcessingStatus.COMPLETED); r.setProcessedAt(Instant.now());
        return StatusResponse.from(r);
    }

    @Transactional
    public void process(UUID id) {
        OcrRequest r = requests.findById(id).orElse(null);
        if (r == null || r.getProcessingStatus() != ProcessingStatus.QUEUED) return;
        r.setProcessingStatus(ProcessingStatus.PROCESSING); requests.saveAndFlush(r);
        try {
            OcrEngine.Result result = engine.recognize(storage.resolve(r.getStorageKey()));
            r.setRecognizedValue(result.value()); r.setOcrConfidence(result.confidence());
            List<String> errors = new ArrayList<>();
            try { readingService.validate(r.getMeter(), result.value(), r.getSubmittedAt(), false, null); }
            catch (ApiException ex) { ex.details().forEach(d -> errors.add(d.message())); }
            if (result.confidence().doubleValue() < properties.ocr().minimumConfidence()) errors.add("Уверенность OCR ниже заданного порога");
            if (errors.isEmpty()) {
                MeterReading reading = createReading(r, result.value(), result.confidence()); r.setReadingId(reading.getId());
                r.setProcessingStatus(ProcessingStatus.COMPLETED);
            } else {
                r.setValidationErrors(String.join("\n", errors)); r.setProcessingStatus(ProcessingStatus.REQUIRES_CONFIRMATION);
            }
        } catch (Exception ex) {
            r.setValidationErrors(ex.getMessage()); r.setProcessingStatus(ProcessingStatus.FAILED);
        }
        r.setProcessedAt(Instant.now()); requests.save(r);
    }

    @Override @Transactional
    public void run(ApplicationArguments args) {
        requests.findByProcessingStatusInOrderByCreatedAtAsc(List.of(ProcessingStatus.QUEUED, ProcessingStatus.PROCESSING)).forEach(r -> {
            if (r.getProcessingStatus() == ProcessingStatus.PROCESSING) { r.setProcessingStatus(ProcessingStatus.QUEUED); requests.save(r); }
            queue.enqueue(r.getId());
        });
    }

    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT) public void queued(Queued event) { queue.enqueue(event.id()); }
    private OcrRequest owned(UUID id, UUID userId) { return requests.findByIdAndUserId(id, userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Запрос OCR не найден")); }
    private MeterReading createReading(OcrRequest r, BigDecimal value, BigDecimal confidence) {
        MeterReading reading = new MeterReading(); reading.setUser(r.getUser()); reading.setMeter(r.getMeter()); reading.setValue(value);
        reading.setSubmittedAt(r.getSubmittedAt()); reading.setSource(ReadingSource.IMAGE_OCR); reading.setProcessingStatus(ProcessingStatus.COMPLETED);
        reading.setOcrConfidence(confidence); return readings.save(reading);
    }
    public record Queued(UUID id) {}
}
