package com.example.metermonitor.storage;

import com.example.metermonitor.common.DomainTypes.ProcessingStatus;
import com.example.metermonitor.config.AppProperties;
import com.example.metermonitor.ocr.OcrRequestRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component @RequiredArgsConstructor
public class ImageRetentionJob {
    private final OcrRequestRepository requests;
    private final ImageStorage storage;
    private final AppProperties properties;

    @Scheduled(cron="${app.upload.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void removeExpiredImages() {
        Instant cutoff=Instant.now().minus(properties.upload().retention());
        requests.findByProcessingStatusInAndProcessedAtBeforeAndImageDeletedAtIsNull(
                List.of(ProcessingStatus.COMPLETED,ProcessingStatus.FAILED),cutoff).forEach(request->{
            storage.delete(request.getStorageKey()); request.setImageDeletedAt(Instant.now());
        });
    }
}
