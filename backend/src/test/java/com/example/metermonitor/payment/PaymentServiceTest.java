package com.example.metermonitor.payment;

import static com.example.metermonitor.common.DomainTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.example.metermonitor.meter.*;
import com.example.metermonitor.reading.*;
import com.example.metermonitor.tariff.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PaymentServiceTest {
    @Test void selectsOverlappingTariffsAndSplitsAChangedBillingPeriod(){
        MeterService meters=mock(MeterService.class); MeterReadingRepository readings=mock(MeterReadingRepository.class); TariffRepository tariffs=mock(TariffRepository.class);
        UUID user=UUID.randomUUID(), id=UUID.randomUUID(); Meter meter=new Meter(); meter.setId(id); meter.setMeterType(MeterType.GAS); meter.setRegion("Moscow"); when(meters.owned(id,user)).thenReturn(meter);
        MeterReading a=reading(meter,"100","2026-01-01T00:00:00Z"), b=reading(meter,"160","2026-01-30T00:00:00Z"); when(readings.findByMeterIdAndProcessingStatusOrderBySubmittedAtAsc(id,ProcessingStatus.COMPLETED)).thenReturn(List.of(a,b));
        Tariff t1=tariff("2.00",LocalDate.of(2026,1,1),LocalDate.of(2026,1,15)), t2=tariff("3.00",LocalDate.of(2026,1,16),LocalDate.of(2026,12,31)); when(tariffs.findOverlapping(any(),any(),any(),any())).thenReturn(List.of(t1,t2));
        var result=new PaymentService(meters,readings,tariffs).calculate(user,id);
        assertThat(result.get(1).consumption()).isEqualByComparingTo("60"); assertThat(result.get(1).payment()).isEqualByComparingTo("150.00");
    }
    @Test void reportsMissingTariffWhenRateIsZero(){
        MeterService meters=mock(MeterService.class); MeterReadingRepository readings=mock(MeterReadingRepository.class); TariffRepository tariffs=mock(TariffRepository.class);
        UUID user=UUID.randomUUID(), id=UUID.randomUUID(); Meter meter=new Meter(); meter.setId(id); meter.setMeterType(MeterType.COLD_WATER); meter.setRegion("Moscow"); when(meters.owned(id,user)).thenReturn(meter);
        MeterReading a=reading(meter,"10","2026-01-01T00:00:00Z"), b=reading(meter,"20","2026-01-31T00:00:00Z"); when(readings.findByMeterIdAndProcessingStatusOrderBySubmittedAtAsc(id,ProcessingStatus.COMPLETED)).thenReturn(List.of(a,b));
        when(tariffs.findOverlapping(any(),any(),any(),any())).thenReturn(List.of(tariff("0",LocalDate.of(2026,1,1),LocalDate.of(2026,12,31))));
        var period=new PaymentService(meters,readings,tariffs).calculate(user,id).get(1);
        assertThat(period.status()).isEqualTo("MISSING_TARIFF"); assertThat(period.payment()).isNull();
    }
    @Test void reportsMissingTariffWhenPartOfPeriodIsUncovered(){
        MeterService meters=mock(MeterService.class); MeterReadingRepository readings=mock(MeterReadingRepository.class); TariffRepository tariffs=mock(TariffRepository.class);
        UUID user=UUID.randomUUID(), id=UUID.randomUUID(); Meter meter=new Meter(); meter.setId(id); meter.setMeterType(MeterType.GAS); meter.setRegion("Moscow"); when(meters.owned(id,user)).thenReturn(meter);
        MeterReading a=reading(meter,"10","2026-01-01T00:00:00Z"), b=reading(meter,"20","2026-01-31T00:00:00Z"); when(readings.findByMeterIdAndProcessingStatusOrderBySubmittedAtAsc(id,ProcessingStatus.COMPLETED)).thenReturn(List.of(a,b));
        when(tariffs.findOverlapping(any(),any(),any(),any())).thenReturn(List.of(tariff("2",LocalDate.of(2026,1,10),LocalDate.of(2026,1,31))));
        var period=new PaymentService(meters,readings,tariffs).calculate(user,id).get(1);
        assertThat(period.status()).isEqualTo("MISSING_TARIFF"); assertThat(period.payment()).isNull();
    }
    @Test void supportsTwoComponentHotWaterTariff(){
        MeterService meters=mock(MeterService.class); MeterReadingRepository readings=mock(MeterReadingRepository.class); TariffRepository tariffs=mock(TariffRepository.class);
        UUID user=UUID.randomUUID(), id=UUID.randomUUID(); Meter meter=new Meter(); meter.setId(id); meter.setMeterType(MeterType.HOT_WATER); meter.setRegion("Moscow"); when(meters.owned(id,user)).thenReturn(meter);
        MeterReading a=reading(meter,"40","2026-01-01T00:00:00Z"), b=reading(meter,"50","2026-01-10T00:00:00Z"); when(readings.findByMeterIdAndProcessingStatusOrderBySubmittedAtAsc(id,ProcessingStatus.COMPLETED)).thenReturn(List.of(a,b));
        Tariff t=tariff("0",LocalDate.of(2026,1,1),LocalDate.of(2026,12,31));
        t.setBillingMethod(TariffBillingMethod.TWO_COMPONENT_HOT_WATER); t.setWaterComponentPerM3(new BigDecimal("70"));
        t.setHeatComponentPerGcal(new BigDecimal("2800")); t.setHeatNormGcalPerM3(new BigDecimal("0.06"));
        when(tariffs.findOverlapping(any(),any(),any(),any())).thenReturn(List.of(t));
        var period=new PaymentService(meters,readings,tariffs).calculate(user,id).get(1);
        assertThat(period.payment()).isEqualByComparingTo("2380.00");
    }
    private MeterReading reading(Meter m,String v,String at){MeterReading r=new MeterReading();r.setId(UUID.randomUUID());r.setMeter(m);r.setValue(new BigDecimal(v));r.setSubmittedAt(Instant.parse(at));return r;}
    private Tariff tariff(String p,LocalDate f,LocalDate t){Tariff x=new Tariff();x.setPricePerUnit(new BigDecimal(p));x.setValidFrom(f);x.setValidTo(t);x.setCurrency("RUB");return x;}
}
