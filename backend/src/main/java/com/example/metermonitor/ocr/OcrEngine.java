package com.example.metermonitor.ocr;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;

public interface OcrEngine {
    Result recognize(Path image);
    record Result(BigDecimal value, BigDecimal confidence, String rawText, Map<String, Object> metadata) {}
}
