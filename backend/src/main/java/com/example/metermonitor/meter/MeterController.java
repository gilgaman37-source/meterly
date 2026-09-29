package com.example.metermonitor.meter;

import com.example.metermonitor.meter.MeterDtos.*;
import com.example.metermonitor.security.CurrentAccount;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/meters") @RequiredArgsConstructor
public class MeterController {
    private final MeterService service; private final CurrentAccount current;
    @PostMapping ResponseEntity<MeterResponse> create(@Valid @RequestBody CreateMeterRequest r) { return ResponseEntity.status(201).body(service.create(current.get(), r)); }
    @GetMapping List<MeterResponse> list() { return service.list(current.get()); }
    @PutMapping("/{id}") MeterResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateMeterRequest r) { return service.update(current.get(), id, r); }
    @DeleteMapping("/{id}") ResponseEntity<Void> disable(@PathVariable UUID id) { service.disable(current.get(), id); return ResponseEntity.noContent().build(); }
}
