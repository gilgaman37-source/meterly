package com.example.metermonitor.reading;

import static com.example.metermonitor.common.DomainTypes.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.example.metermonitor.common.ApiException;
import com.example.metermonitor.config.AppProperties;
import com.example.metermonitor.meter.*;
import com.example.metermonitor.reading.ReadingDtos.ManualReadingRequest;
import com.example.metermonitor.user.Account;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class ReadingServiceTest {
    @Mock MeterReadingRepository readings; @Mock MeterService meters; ReadingService service; Account user; Meter meter;
    @BeforeEach void setup(){
        var props=new AppProperties(new AppProperties.Jwt("x",Duration.ofHours(1)),new AppProperties.Reading(new BigDecimal("999999"),Duration.ofMinutes(5),new BigDecimal("0.000001")),new AppProperties.Upload(Path.of("x"),10,List.of(),Duration.ofDays(1)),new AppProperties.Ocr("tesseract",.8,"","","","python",false),new AppProperties.Cors(List.of()));
        service=new ReadingService(readings,meters,props); user=new Account(); user.setId(UUID.randomUUID()); meter=new Meter(); meter.setId(UUID.randomUUID()); meter.setUser(user); meter.setActive(true); meter.setMeterType(MeterType.COLD_WATER);
        lenient().when(meters.owned(meter.getId(),user.getId())).thenReturn(meter); lenient().when(readings.save(any())).thenAnswer(i->i.getArgument(0));
    }
    @Test void acceptsValidManualReading(){
        when(readings.findTopByMeterIdAndProcessingStatusOrderBySubmittedAtDesc(meter.getId(),ProcessingStatus.COMPLETED)).thenReturn(Optional.empty());
        var result=service.submit(user,meter.getId(),new ManualReadingRequest(new BigDecimal("123.45"),Instant.now(),false,null));
        assertThat(result.value()).isEqualByComparingTo("123.45"); assertThat(result.source()).isEqualTo(ReadingSource.MANUAL);
    }
    @Test void rejectsReadingLowerThanPrevious(){
        MeterReading previous=new MeterReading(); previous.setValue(new BigDecimal("130"));
        when(readings.findTopByMeterIdAndProcessingStatusOrderBySubmittedAtDesc(meter.getId(),ProcessingStatus.COMPLETED)).thenReturn(Optional.of(previous));
        assertThatThrownBy(()->service.submit(user,meter.getId(),new ManualReadingRequest(new BigDecimal("120"),Instant.now(),false,null))).isInstanceOf(ApiException.class).hasMessage("Ошибка проверки данных");
    }
    @Test void retrievesOwnedHistory(){
        when(readings.findAll(any(Specification.class),any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        assertThat(service.history(user,meter.getId(),null,null,null,null,null,PageRequest.of(0,10))).isEmpty(); verify(meters).owned(meter.getId(),user.getId());
    }
    @Test void retrievesCurrentUserHistoryAcrossMeters(){
        when(readings.findAll(any(Specification.class),any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        assertThat(service.userHistory(user,null,null,null,MeterType.COLD_WATER,null,null,PageRequest.of(0,10))).isEmpty();
        verify(meters, never()).owned(any(), any());
    }
}
