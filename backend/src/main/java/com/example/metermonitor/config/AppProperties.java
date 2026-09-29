package com.example.metermonitor.config;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(
        Jwt jwt,
        Reading reading,
        Upload upload,
        Ocr ocr,
        Cors cors
) {
    public record Jwt(String secret, Duration expiration) {}
    public record Reading(BigDecimal maximumValue, Duration futureTolerance, BigDecimal duplicateTolerance) {}
    public record Upload(Path directory, long maximumBytes, List<String> allowedMimeTypes, Duration retention) {}
    public record Ocr(
            String engine,
            double minimumConfidence,
            String tessdataPath,
            String digitModelPath,
            String digitPredictScript,
            String pythonExecutable,
            boolean workerEnabled
    ) {}
    public record Cors(List<String> allowedOrigins) {}
}
