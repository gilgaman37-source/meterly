package com.example.metermonitor.reading;

import com.example.metermonitor.common.DomainTypes.*;
import com.example.metermonitor.reading.ReadingDtos.ReadingResponse;
import com.example.metermonitor.security.CurrentAccount;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/readings") @RequiredArgsConstructor
public class UserReadingController {
    private final ReadingService service; private final CurrentAccount current;

    @GetMapping Page<ReadingResponse> history(
            @RequestParam(required=false) UUID meterId,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required=false) MeterType meterType, @RequestParam(required=false) ReadingSource source,
            @RequestParam(required=false) ProcessingStatus status,
            @PageableDefault(size=20, sort="submittedAt", direction=Sort.Direction.DESC) Pageable pageable) {
        return service.userHistory(current.get(), meterId, from, to, meterType, source, status, pageable);
    }
}
