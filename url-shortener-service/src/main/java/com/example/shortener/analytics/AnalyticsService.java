package com.example.shortener.analytics;

import com.example.shortener.service.BadRequestException;
import com.example.shortener.domain.ShortUrl;
import com.example.shortener.repository.ClickEventRepository;
import com.example.shortener.service.ShortUrlService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class AnalyticsService {

    static final int DEFAULT_RANGE_DAYS = 30;
    static final int MAX_RANGE_DAYS = 366;
    static final int TOP_N = 10;

    private final ShortUrlService shortUrlService;
    private final ClickEventRepository clicks;
    private final Clock clock;

    public AnalyticsService(ShortUrlService shortUrlService, ClickEventRepository clicks, Clock clock) {
        this.shortUrlService = shortUrlService;
        this.clicks = clicks;
        this.clock = clock;
    }

    /** Stats for one link, for the inclusive UTC-day range [from, to]. */
    @Transactional(readOnly = true)
    public LinkStats stats(String code, String ownerKeyId, LocalDate from, LocalDate to) {
        ShortUrl link = shortUrlService.getOwned(code, ownerKeyId);

        LocalDate end = to != null ? to : LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new BadRequestException("'from' must be on or before 'to'");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
            throw new BadRequestException("Date range must be at most " + MAX_RANGE_DAYS + " days");
        }

        Long id = link.getId();
        return new LinkStats(
                code,
                clicks.countByShortUrlId(id),
                start,
                end,
                clicks.dailyCounts(id, start, end),
                clicks.topReferrers(id, start, end, PageRequest.of(0, TOP_N)),
                clicks.userAgentFamilies(id, start, end));
    }

    public record LinkStats(String shortCode, long totalClicks, LocalDate from, LocalDate to,
                            List<DailyCount> daily, List<CountByKey> topReferrers, List<CountByKey> userAgents) {
    }
}
