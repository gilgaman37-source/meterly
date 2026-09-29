package com.example.metermonitor.common;

import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException ex) {
        return ResponseEntity.status(ex.status()).body(new ApiError(Instant.now(), ex.status().value(), ex.getMessage(), ex.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException ex) {
        List<ApiException.FieldIssue> details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new ApiException.FieldIssue(e.getField(), e.getDefaultMessage())).toList();
        return ResponseEntity.badRequest().body(new ApiError(Instant.now(), 400, "Ошибка проверки данных", details));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> invalidParameter(ConstraintViolationException ex) {
        var details = ex.getConstraintViolations().stream()
                .map(e -> new ApiException.FieldIssue(e.getPropertyPath().toString(), e.getMessage())).toList();
        return ResponseEntity.badRequest().body(new ApiError(Instant.now(), 400, "Ошибка проверки данных", details));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError(Instant.now(), 413, "Загруженное изображение слишком велико", List.of()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(Instant.now(), 409, "Запрос конфликтует с существующей записью", List.of()));
    }

    public record ApiError(Instant timestamp, int status, String error, List<ApiException.FieldIssue> details) {}
}
