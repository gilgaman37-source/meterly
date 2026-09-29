package com.example.metermonitor.analytics;

import com.example.metermonitor.common.DomainTypes.MeterType;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/analytics") @RequiredArgsConstructor
public class AnalyticsController {
    private final AnalyticsService service;
    private LocalDate from(LocalDate f){return f==null?LocalDate.now().minusYears(1):f;} private LocalDate to(LocalDate t){return t==null?LocalDate.now():t;}
    @GetMapping("/payments/monthly") List<Map<String,Object>> payments(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@RequestParam(required=false) MeterType meterType){return service.paymentsMonthly(from(from),to(to),meterType);}
    @GetMapping("/payments/by-meter-type") List<Map<String,Object>> byType(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required=false) MeterType meterType){return service.paymentsByType(from(from),to(to),meterType);}
    @GetMapping("/consumption/monthly") List<Map<String,Object>> consumption(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@RequestParam(required=false) MeterType meterType){return service.consumption(from(from),to(to),meterType);}
    @GetMapping("/ocr/statistics") List<Map<String,Object>> ocr(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required=false) MeterType meterType){return service.ocr(from(from),to(to),meterType);}
    @GetMapping("/readings/monthly") List<Map<String,Object>> readings(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required=false) MeterType meterType){return service.readingsMonthly(from(from),to(to),meterType);}
    @GetMapping("/summary") Map<String,Object> summary(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required=false) MeterType meterType){return service.summary(from(from),to(to),meterType);}
}
