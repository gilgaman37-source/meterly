package com.example.metermonitor.tariff;

import com.example.metermonitor.common.DomainTypes.MeterType;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface TariffRepository extends JpaRepository<Tariff, UUID> {
    @Query("""
        select t from Tariff t where t.meterType = :type and lower(t.region) = lower(:region)
        and t.validFrom <= :end and (t.validTo is null or t.validTo >= :start)
        order by t.validFrom asc
        """)
    List<Tariff> findOverlapping(@Param("type") MeterType type, @Param("region") String region,
                                 @Param("start") LocalDate start, @Param("end") LocalDate end);
}
