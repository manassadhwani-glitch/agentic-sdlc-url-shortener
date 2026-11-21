package com.example.shortener.service;

public class AliasTakenException extends RuntimeException {
    private final String shortCode;

    public AliasTakenException(String shortCode) {
        super("Alias is already taken: " + shortCode);
        this.shortCode = shortCode;
    }

    public String getShortCode() {
        return shortCode;
    }
}
