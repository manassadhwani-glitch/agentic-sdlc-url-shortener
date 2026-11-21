package com.example.shortener.analytics;

import java.time.LocalDate;

public record DailyCount(LocalDate day, Long clicks) {
}
