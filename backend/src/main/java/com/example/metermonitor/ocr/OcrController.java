package com.example.metermonitor.ocr;

import com.example.metermonitor.ocr.OcrDtos.*;
import com.example.metermonitor.security.CurrentAccount;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequiredArgsConstructor
public class OcrController {
    private final OcrRequestService service; private final CurrentAccount current;
    @PostMapping(value="/api/meters/{meterId}/readings/image", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<QueuedResponse> upload(@PathVariable UUID meterId, @RequestPart("image") MultipartFile image) {
        return ResponseEntity.accepted().body(service.upload(current.get(), meterId, image));
    }
    @GetMapping("/api/reading-requests/{id}") StatusResponse status(@PathVariable UUID id) { return service.status(current.get(), id); }
    @PatchMapping("/api/reading-requests/{id}/confirm") StatusResponse confirm(@PathVariable UUID id, @Valid @RequestBody ConfirmRequest r) {
        return service.confirm(current.get(), id, r.value());
    }
}
