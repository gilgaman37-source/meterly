package com.example.metermonitor.ocr;

import com.example.metermonitor.common.ApiException;
import com.example.metermonitor.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ocr.engine", havingValue = "digit-cells")
public class PythonDigitCellOcrEngine implements OcrEngine {
    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    private final AppProperties properties;
    private final ObjectMapper mapper;

    @Override
    public Result recognize(Path image) {
        Path model = Path.of(properties.ocr().digitModelPath()).toAbsolutePath().normalize();
        Path script = Path.of(properties.ocr().digitPredictScript()).toAbsolutePath().normalize();
        if (!Files.exists(model)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OCR model file is missing: " + model);
        }
        if (!Files.exists(script)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OCR prediction script is missing: " + script);
        }

        ProcessBuilder builder = new ProcessBuilder(
                pythonExecutable(),
                script.toString(),
                "--model", model.toString(),
                "--image", image.toAbsolutePath().normalize().toString()
        );
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            boolean completed = process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!completed) {
                process.destroyForcibly();
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OCR model timeout");
            }
            if (process.exitValue() != 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OCR model failed: " + output);
            }

            JsonNode root = mapper.readTree(output);
            BigDecimal value = new BigDecimal(root.path("value").asText());
            BigDecimal confidence = BigDecimal.valueOf(root.path("confidence").asDouble())
                    .setScale(4, RoundingMode.HALF_UP);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("recognitionMode", "python-digit-cells");
            metadata.put("digits", root.path("digits").asText());
            metadata.put("modelPath", model.toString());
            return new Result(value, confidence, output, metadata);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OCR model failed: " + ex.getMessage());
        }
    }

    private String pythonExecutable() {
        String configured = properties.ocr().pythonExecutable();
        return configured == null || configured.isBlank() ? "python" : configured;
    }
}
