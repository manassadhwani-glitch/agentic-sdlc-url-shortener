package com.example.shortener.service;

public class LinkNotFoundException extends RuntimeException {
    private final String shortCode;

    public LinkNotFoundException(String shortCode) {
        super("Short link not found: " + shortCode);
        this.shortCode = shortCode;
    }

    public String getShortCode() {
        return shortCode;
    }
}
