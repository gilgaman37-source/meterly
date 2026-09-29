package com.example.metermonitor.ocr;

import static com.example.metermonitor.common.DomainTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.example.metermonitor.config.AppProperties;
import com.example.metermonitor.meter.*;
import com.example.metermonitor.queue.OcrJobQueue;
import com.example.metermonitor.reading.*;
import com.example.metermonitor.storage.ImageStorage;
import com.example.metermonitor.user.Account;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class OcrRequestServiceTest {
    OcrRequestRepository requests=mock(OcrRequestRepository.class); MeterReadingRepository readings=mock(MeterReadingRepository.class); MeterService meters=mock(MeterService.class); ReadingService validation=mock(ReadingService.class); ImageStorage storage=mock(ImageStorage.class); OcrEngine engine=mock(OcrEngine.class); OcrJobQueue queue=mock(OcrJobQueue.class); org.springframework.context.ApplicationEventPublisher events=mock(org.springframework.context.ApplicationEventPublisher.class); AppProperties props; OcrRequest request;
    @BeforeEach void setup(){ props=new AppProperties(new AppProperties.Jwt("x",Duration.ofHours(1)),new AppProperties.Reading(BigDecimal.TEN,Duration.ZERO,BigDecimal.ZERO),new AppProperties.Upload(Path.of("x"),10,List.of(),Duration.ofDays(1)),new AppProperties.Ocr("tesseract",.8,"","","","python",false),new AppProperties.Cors(List.of())); request=new OcrRequest();request.setId(UUID.randomUUID());request.setProcessingStatus(ProcessingStatus.QUEUED);request.setStorageKey("a.png");request.setSubmittedAt(Instant.now());request.setUser(new Account());Meter m=new Meter();m.setId(UUID.randomUUID());request.setMeter(m);when(requests.findById(request.getId())).thenReturn(Optional.of(request));when(storage.resolve("a.png")).thenReturn(Path.of("a.png"));when(readings.save(any())).thenAnswer(i->{MeterReading r=i.getArgument(0);r.setId(UUID.randomUUID());return r;}); }
    @Test void completesHighConfidenceResult(){when(engine.recognize(any())).thenReturn(new OcrEngine.Result(new BigDecimal("42.2"),new BigDecimal("0.95"),"42.2",Map.of()));service().process(request.getId());assertThat(request.getProcessingStatus()).isEqualTo(ProcessingStatus.COMPLETED);assertThat(request.getReadingId()).isNotNull();}
    @Test void requiresConfirmationForLowConfidence(){when(engine.recognize(any())).thenReturn(new OcrEngine.Result(new BigDecimal("42.2"),new BigDecimal("0.60"),"42.2",Map.of()));service().process(request.getId());assertThat(request.getProcessingStatus()).isEqualTo(ProcessingStatus.REQUIRES_CONFIRMATION);}
    @Test void recordsFailedOcrFlow(){when(engine.recognize(any())).thenThrow(new IllegalStateException("unreadable"));service().process(request.getId());assertThat(request.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);}
    @Test void restoresQueuedAndInterruptedJobs(){OcrRequest interrupted=new OcrRequest();interrupted.setId(UUID.randomUUID());interrupted.setProcessingStatus(ProcessingStatus.PROCESSING);when(requests.findByProcessingStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of(request,interrupted));service().run(null);verify(queue).enqueue(request.getId());verify(queue).enqueue(interrupted.getId());assertThat(interrupted.getProcessingStatus()).isEqualTo(ProcessingStatus.QUEUED);}
    private OcrRequestService service(){return new OcrRequestService(requests,readings,meters,validation,storage,engine,queue,props,events);}
}
