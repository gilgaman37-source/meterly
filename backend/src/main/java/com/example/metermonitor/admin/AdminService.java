package com.example.metermonitor.admin;

import com.example.metermonitor.admin.AdminDtos.*;
import com.example.metermonitor.common.*;
import com.example.metermonitor.common.DomainTypes.*;
import com.example.metermonitor.tariff.*;
import com.example.metermonitor.user.*;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class AdminService {
    private final AccountRepository accounts;
    private final TariffRepository tariffs;

    @Transactional(readOnly = true)
    public List<AdminUserResponse> users() {
        return accounts.findAll(Sort.by(Sort.Direction.ASC, "createdAt")).stream().map(AdminUserResponse::from).toList();
    }

    @Transactional
    public AdminUserResponse updateUser(UUID id, UpdateUserRequest request, UUID currentAdminId) {
        Account user = accounts.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        if (id.equals(currentAdminId) && (!request.enabled() || request.role() != Role.ADMIN)) {
            throw new ApiException(HttpStatus.CONFLICT, "Нельзя снять права администратора или отключить текущий аккаунт");
        }
        user.setRole(request.role());
        user.setEnabled(request.enabled());
        return AdminUserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public List<TariffResponse> tariffs() {
        return tariffs.findAll(Sort.by(Sort.Direction.DESC, "validFrom")).stream().map(TariffResponse::from).toList();
    }

    @Transactional
    public TariffResponse createTariff(TariffRequest request) {
        Tariff tariff = new Tariff();
        copy(tariff, request);
        return TariffResponse.from(tariffs.save(tariff));
    }

    @Transactional
    public TariffResponse updateTariff(UUID id, TariffRequest request) {
        Tariff tariff = tariffs.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Тариф не найден"));
        copy(tariff, request);
        return TariffResponse.from(tariff);
    }

    @Transactional
    public void deleteTariff(UUID id) {
        if (!tariffs.existsById(id)) throw new ApiException(HttpStatus.NOT_FOUND, "Тариф не найден");
        tariffs.deleteById(id);
    }

    private void copy(Tariff tariff, TariffRequest request) {
        if (request.validTo() != null && request.validTo().isBefore(request.validFrom())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Дата окончания тарифа не может быть раньше даты начала");
        }
        tariff.setMeterType(request.meterType());
        tariff.setBillingMethod(request.billingMethod());
        tariff.setCurrency(request.currency().trim().toUpperCase(Locale.ROOT));
        tariff.setValidFrom(request.validFrom());
        tariff.setValidTo(request.validTo());
        tariff.setRegion(request.region().trim());
        tariff.setSourceUrl(request.sourceUrl() == null || request.sourceUrl().isBlank() ? null : request.sourceUrl().trim());

        if (request.billingMethod() == TariffBillingMethod.TWO_COMPONENT_HOT_WATER) {
            if (request.meterType() != MeterType.HOT_WATER) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Двухкомпонентный тариф допустим только для горячей воды");
            }
            if (request.waterComponentPerM3() == null || request.heatComponentPerGcal() == null || request.heatNormGcalPerM3() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Для ГВС укажите компонент воды, компонент тепла и норматив Гкал/м³");
            }
            tariff.setWaterComponentPerM3(request.waterComponentPerM3());
            tariff.setHeatComponentPerGcal(request.heatComponentPerGcal());
            tariff.setHeatNormGcalPerM3(request.heatNormGcalPerM3());
            tariff.setPricePerUnit(tariff.effectivePricePerUnit());
        } else {
            if (request.pricePerUnit() == null || request.pricePerUnit().signum() <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Для одиночного тарифа укажите положительную цену за м³");
            }
            tariff.setPricePerUnit(request.pricePerUnit());
            tariff.setWaterComponentPerM3(null);
            tariff.setHeatComponentPerGcal(null);
            tariff.setHeatNormGcalPerM3(null);
        }
        if (tariff.getPricePerUnit() == null || tariff.getPricePerUnit().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Эффективный тариф должен быть положительным");
        }
    }
}
