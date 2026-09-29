package com.example.metermonitor.reading;

import com.example.metermonitor.common.DomainTypes.*;
import com.example.metermonitor.reading.ReadingDtos.*;
import com.example.metermonitor.security.CurrentAccount;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/meters/{meterId}/readings") @RequiredArgsConstructor
public class ReadingController {
    private final ReadingService service; private final CurrentAccount current;
    @PostMapping ResponseEntity<ReadingResponse> submit(@PathVariable UUID meterId, @Valid @RequestBody ManualReadingRequest r) {
        return ResponseEntity.status(201).body(service.submit(current.get(), meterId, r));
    }
    @GetMapping Page<ReadingResponse> history(@PathVariable UUID meterId,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required=false) MeterType meterType, @RequestParam(required=false) ReadingSource source,
            @RequestParam(required=false) ProcessingStatus status,
            @PageableDefault(size=20, sort="submittedAt", direction=Sort.Direction.DESC) Pageable pageable) {
        return service.history(current.get(), meterId, from, to, meterType, source, status, pageable);
    }
}
