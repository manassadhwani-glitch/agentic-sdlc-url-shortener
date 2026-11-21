package com.example.shortener.repository;

import com.example.shortener.analytics.CountByKey;
import com.example.shortener.analytics.DailyCount;
import com.example.shortener.domain.ClickEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    long countByShortUrlId(Long shortUrlId);

    @Query("""
            select new com.example.shortener.analytics.DailyCount(c.occurredOn, count(c))
            from ClickEvent c
            where c.shortUrlId = :id and c.occurredOn between :from and :to
            group by c.occurredOn
            order by c.occurredOn
            """)
    List<DailyCount> dailyCounts(@Param("id") Long shortUrlId,
                                 @Param("from") LocalDate from,
                                 @Param("to") LocalDate to);

    @Query("""
            select new com.example.shortener.analytics.CountByKey(c.referrerHost, count(c))
            from ClickEvent c
            where c.shortUrlId = :id and c.occurredOn between :from and :to
            group by c.referrerHost
            order by count(c) desc
            """)
    List<CountByKey> topReferrers(@Param("id") Long shortUrlId,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to,
                                  Pageable limit);

    @Query("""
            select new com.example.shortener.analytics.CountByKey(c.uaFamily, count(c))
            from ClickEvent c
            where c.shortUrlId = :id and c.occurredOn between :from and :to
            group by c.uaFamily
            order by count(c) desc
            """)
    List<CountByKey> userAgentFamilies(@Param("id") Long shortUrlId,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);
}
