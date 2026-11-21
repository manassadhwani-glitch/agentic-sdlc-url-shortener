package com.example.shortener.service;

public class LinkGoneException extends RuntimeException {
    private final String shortCode;

    public LinkGoneException(String shortCode) {
        super("Short link is no longer active: " + shortCode);
        this.shortCode = shortCode;
    }

    public String getShortCode() {
        return shortCode;
    }
}
