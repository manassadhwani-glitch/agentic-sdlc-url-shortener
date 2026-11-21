package com.example.shortener.code;

/** Base62 encoding of non-negative longs using [0-9A-Za-z]. */
public final class Base62 {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = ALPHABET.length();

    private Base62() {
    }

    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative: " + value);
        }
        if (value == 0) {
            return "0";
        }
        StringBuilder sb = new StringBuilder();
        long v = value;
        while (v > 0) {
            sb.append(ALPHABET.charAt((int) (v % BASE)));
            v /= BASE;
        }
        return sb.reverse().toString();
    }

    /** Encodes and left-pads with '0' to the given width. */
    public static String encode(long value, int width) {
        String raw = encode(value);
        if (raw.length() > width) {
            throw new IllegalArgumentException("value " + value + " does not fit in " + width + " chars");
        }
        return "0".repeat(width - raw.length()) + raw;
    }

    public static long decode(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
        long result = 0;
        for (int i = 0; i < text.length(); i++) {
            int digit = ALPHABET.indexOf(text.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("invalid base62 character: " + text.charAt(i));
            }
            result = Math.addExact(Math.multiplyExact(result, BASE), digit);
        }
        return result;
    }
}
