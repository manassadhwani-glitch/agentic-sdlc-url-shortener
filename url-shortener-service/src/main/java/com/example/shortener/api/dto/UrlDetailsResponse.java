package com.example.shortener.api.dto;

import com.example.shortener.domain.LinkStatus;
import com.example.shortener.domain.ShortUrl;

import java.time.Instant;

public record UrlDetailsResponse(String shortCode, String shortUrl, String longUrl, LinkStatus status,
                                 boolean customAlias, Instant createdAt) {

    public static UrlDetailsResponse from(ShortUrl link, String baseUrl) {
        return new UrlDetailsResponse(link.getShortCode(), baseUrl + "/" + link.getShortCode(), link.getLongUrl(),
                link.getStatus(), link.isCustomAlias(), link.getCreatedAt());
    }
}
