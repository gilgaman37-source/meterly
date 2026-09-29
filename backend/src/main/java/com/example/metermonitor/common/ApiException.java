package com.example.metermonitor.common;

import java.util.List;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final List<FieldIssue> details;

    public ApiException(HttpStatus status, String message) {
        this(status, message, List.of());
    }

    public ApiException(HttpStatus status, String message, List<FieldIssue> details) {
        super(message);
        this.status = status;
        this.details = details;
    }

    public HttpStatus status() { return status; }
    public List<FieldIssue> details() { return details; }
    public record FieldIssue(String field, String message) {}
}
