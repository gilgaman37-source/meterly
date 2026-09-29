package com.example.metermonitor.ocr;

import com.example.metermonitor.config.AppProperties;
import com.example.metermonitor.queue.OcrJobQueue;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class OcrWorker {
    private final OcrJobQueue queue;
    private final OcrRequestService service;
    private final AppProperties properties;

    @Scheduled(fixedDelayString="${app.ocr.worker-delay-ms:250}")
    public void processNext() {
        if (properties.ocr().workerEnabled()) queue.poll().ifPresent(service::process);
    }
}
