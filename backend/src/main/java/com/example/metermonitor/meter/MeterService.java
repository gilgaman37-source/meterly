package com.example.metermonitor.meter;

import com.example.metermonitor.common.ApiException;
import com.example.metermonitor.meter.MeterDtos.*;
import com.example.metermonitor.user.Account;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class MeterService {
    private final MeterRepository meters;

    @Transactional public MeterResponse create(Account user, CreateMeterRequest r) {
        if (meters.existsByUserIdAndSerialNumberIgnoreCase(user.getId(), r.serialNumber().trim()))
            throw new ApiException(HttpStatus.CONFLICT, "Счётчик с таким серийным номером уже существует");
        Meter m = new Meter(); m.setUser(user); copy(m, r.serialNumber(), r.meterType(), r.description(), r.region());
        return MeterResponse.from(meters.save(m));
    }
    @Transactional(readOnly = true) public List<MeterResponse> list(Account user) {
        return meters.findByUserIdOrderByCreatedAtDesc(user.getId()).stream().map(MeterResponse::from).toList();
    }
    @Transactional public MeterResponse update(Account user, UUID id, UpdateMeterRequest r) {
        Meter m = owned(id, user.getId());
        if (meters.existsByUserIdAndSerialNumberIgnoreCaseAndIdNot(user.getId(), r.serialNumber().trim(), id))
            throw new ApiException(HttpStatus.CONFLICT, "Счётчик с таким серийным номером уже существует");
        copy(m, r.serialNumber(), r.meterType(), r.description(), r.region()); m.setActive(r.active());
        return MeterResponse.from(m);
    }
    @Transactional public void disable(Account user, UUID id) { owned(id, user.getId()).setActive(false); }
    @Transactional(readOnly = true) public Meter owned(UUID id, UUID userId) {
        return meters.findByIdAndUserId(id, userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Счётчик не найден"));
    }
    private void copy(Meter m, String serial, com.example.metermonitor.common.DomainTypes.MeterType type, String description, String region) {
        m.setSerialNumber(serial.trim()); m.setMeterType(type); m.setDescription(description == null ? null : description.trim());
        m.setRegion(region == null || region.isBlank() ? "Московская область" : region.trim());
    }
}
