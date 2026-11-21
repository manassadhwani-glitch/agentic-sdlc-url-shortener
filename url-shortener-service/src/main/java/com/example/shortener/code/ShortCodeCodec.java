package com.example.shortener.code;

import java.util.regex.Pattern;

/**
 * Turns a sequential database id into a fixed-length, non-sequential short code.
 *
 * <p>Design: ids live in a 41-bit space (2^41 ≈ 2.2 trillion links, which fits in 7 base62 chars
 * because 62^7 ≈ 3.5 trillion). We apply two bijections inside that space: multiply by an odd constant
 * (mod 2^41) and XOR with a secret. Because both are bijections, distinct ids always give distinct codes,
 * so there are no collisions and no retry loop.
 *
 * <p>This is <b>obfuscation, not security</b>: it stops people from guessing neighbouring links
 * by incrementing a code, but it is not a cryptographic guarantee.
 */
public final class ShortCodeCodec {

    public static final int CODE_LENGTH = 7;
    public static final Pattern GENERATED_CODE = Pattern.compile("^[0-9A-Za-z]{" + CODE_LENGTH + "}$");

    private static final int BITS = 41;
    private static final long MASK = (1L << BITS) - 1;
    private static final long MULTIPLIER = 0x5DEECE66DL & MASK | 1L; // odd => invertible mod 2^41

    private final long secret;

    public ShortCodeCodec(long secret) {
        this.secret = secret & MASK;
    }

    public String encode(long id) {
        if (id < 0 || id > MASK) {
            throw new IllegalArgumentException("id out of range for " + CODE_LENGTH + "-char codes: " + id);
        }
        long scrambled = ((id * MULTIPLIER) & MASK) ^ secret;
        return Base62.encode(scrambled, CODE_LENGTH);
    }

    public static boolean looksGenerated(String code) {
        return code != null && GENERATED_CODE.matcher(code).matches();
    }
}
