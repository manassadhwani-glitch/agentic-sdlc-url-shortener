package com.example.shortener.api.dto;

import com.example.shortener.domain.ShortUrl;

import java.time.Instant;

public record CreateUrlResponse(String shortCode, String shortUrl, String longUrl, Instant createdAt) {

    public static CreateUrlResponse from(ShortUrl link, String baseUrl) {
        return new CreateUrlResponse(link.getShortCode(), baseUrl + "/" + link.getShortCode(),
                link.getLongUrl(), link.getCreatedAt());
    }
}
