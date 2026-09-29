package com.example.metermonitor.analytics;

import com.example.metermonitor.common.DomainTypes.MeterType;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class AnalyticsService {
    private final NamedParameterJdbcTemplate jdbc;

    public List<Map<String,Object>> paymentsMonthly(LocalDate from, LocalDate to, MeterType type) {
        return jdbc.queryForList(baseCte() + """
            SELECT to_char(date_trunc('month', submitted_at), 'YYYY-MM') AS "month",
                   ROUND(SUM(GREATEST(value - previous_value, 0) * COALESCE(effective_price_per_unit, 0)), 2) total_payment,
                   ROUND(AVG(GREATEST(value - previous_value, 0) * COALESCE(effective_price_per_unit, 0)), 2) average_payment
            FROM priced WHERE submitted_at::date BETWEEN :from AND :to AND (CAST(:type AS varchar) IS NULL OR meter_type = CAST(:type AS varchar))
            GROUP BY 1 ORDER BY 1
            """, params(from,to,type));
    }
    public List<Map<String,Object>> paymentsByType(LocalDate from, LocalDate to, MeterType type) {
        return jdbc.queryForList(baseCte() + """
            SELECT to_char(date_trunc('month', submitted_at), 'YYYY-MM') AS "month", meter_type,
                   ROUND(SUM(GREATEST(value - previous_value, 0) * COALESCE(effective_price_per_unit, 0)), 2) total_payment
            FROM priced WHERE submitted_at::date BETWEEN :from AND :to AND (CAST(:type AS varchar) IS NULL OR meter_type = CAST(:type AS varchar))
            GROUP BY 1,2 ORDER BY 1,2
            """, params(from,to,type));
    }
    public List<Map<String,Object>> consumption(LocalDate from, LocalDate to, MeterType type) {
        return jdbc.queryForList(baseCte() + """
            SELECT to_char(date_trunc('month', submitted_at), 'YYYY-MM') AS "month", meter_type,
                   ROUND(AVG(GREATEST(value - previous_value, 0)), 3) average_consumption,
                   COUNT(*) reading_count
            FROM priced WHERE submitted_at::date BETWEEN :from AND :to AND (CAST(:type AS varchar) IS NULL OR meter_type = CAST(:type AS varchar))
            GROUP BY 1,2 ORDER BY 1,2
            """, params(from,to,type));
    }
    public List<Map<String,Object>> ocr(LocalDate from, LocalDate to, MeterType type) {
        return jdbc.queryForList("""
            WITH grouped AS (
              SELECT to_char(date_trunc('month', o.created_at), 'YYYY-MM') AS "month", o.processing_status,
                     COUNT(*) request_count,
                     ROUND(AVG(o.ocr_confidence),4) average_confidence
              FROM ocr_requests o JOIN meters m ON m.id=o.meter_id
              WHERE o.created_at::date BETWEEN :from AND :to AND (CAST(:type AS varchar) IS NULL OR m.meter_type = CAST(:type AS varchar))
              GROUP BY 1,2
            )
            SELECT "month", processing_status, request_count,
                   ROUND(100.0 * request_count / SUM(request_count) OVER (PARTITION BY "month"), 2) percentage,
                   average_confidence
            FROM grouped ORDER BY "month", processing_status
            """, params(from,to,type));
    }
    public List<Map<String,Object>> readingsMonthly(LocalDate from, LocalDate to, MeterType type) {
        return jdbc.queryForList("""
            SELECT to_char(date_trunc('month', r.submitted_at), 'YYYY-MM') AS "month", r.source, COUNT(*) reading_count
            FROM meter_readings r JOIN meters m ON m.id=r.meter_id
            WHERE r.submitted_at::date BETWEEN :from AND :to AND (CAST(:type AS varchar) IS NULL OR m.meter_type = CAST(:type AS varchar))
            GROUP BY 1,2 ORDER BY 1,2
            """, params(from,to,type));
    }
    public Map<String,Object> summary(LocalDate from, LocalDate to, MeterType type) {
        return jdbc.queryForMap(baseCte() + """
            SELECT COALESCE(ROUND(SUM(GREATEST(value - previous_value, 0)),3),0) total_consumption,
                   COALESCE(ROUND(SUM(GREATEST(value - previous_value, 0) * COALESCE(effective_price_per_unit,0)),2),0) total_payments,
                   COUNT(DISTINCT user_id) active_users,
                   COUNT(DISTINCT meter_id) active_meters,
                   COUNT(*) submitted_readings
            FROM priced WHERE submitted_at::date BETWEEN :from AND :to AND (CAST(:type AS varchar) IS NULL OR meter_type = CAST(:type AS varchar))
            """, params(from,to,type));
    }
    private Map<String,Object> params(LocalDate f, LocalDate t, MeterType type) { Map<String,Object> p=new HashMap<>(); p.put("from",f);p.put("to",t);p.put("type",type==null?null:type.name());return p; }
    private String baseCte() { return """
        WITH ordered AS (
          SELECT r.*, m.meter_type, m.region,
                 CASE WHEN r.reset_event THEN NULL ELSE LAG(r.value) OVER (PARTITION BY r.meter_id ORDER BY r.submitted_at) END previous_value
          FROM meter_readings r JOIN meters m ON m.id=r.meter_id WHERE r.processing_status='COMPLETED'
        ), priced AS (
          SELECT o.*, t.effective_price_per_unit FROM ordered o
          LEFT JOIN LATERAL (
             SELECT CASE
                    WHEN t.billing_method='TWO_COMPONENT_HOT_WATER' THEN t.water_component_per_m3 + t.heat_component_per_gcal * t.heat_norm_gcal_per_m3
                    ELSE t.price_per_unit
                    END effective_price_per_unit
             FROM tariffs t WHERE t.meter_type=o.meter_type AND lower(t.region)=lower(o.region)
             AND t.valid_from<=o.submitted_at::date AND (t.valid_to IS NULL OR t.valid_to>=o.submitted_at::date)
             ORDER BY t.valid_from DESC LIMIT 1) t ON true
        )
        """; }
}
