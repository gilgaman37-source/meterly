package com.example.metermonitor.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class AnalyticsServiceTest {
    @Test void delegatesAggregationToPostgresql(){NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of(Map.of("month","2026-07","total_payment",123)));var rows=new AnalyticsService(jdbc).paymentsMonthly(LocalDate.parse("2026-01-01"),LocalDate.parse("2026-12-31"),null);assertThat(rows).hasSize(1);verify(jdbc).queryForList(contains("date_trunc"),anyMap());}
}
