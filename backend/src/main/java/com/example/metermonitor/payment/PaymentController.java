package com.example.metermonitor.payment;

import com.example.metermonitor.security.CurrentAccount;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/meters/{meterId}/payments") @RequiredArgsConstructor
public class PaymentController {
    private final PaymentService service; private final CurrentAccount current;
    @GetMapping List<PaymentService.PaymentPeriod> payments(@PathVariable UUID meterId) { return service.calculate(current.get().getId(), meterId); }
}
