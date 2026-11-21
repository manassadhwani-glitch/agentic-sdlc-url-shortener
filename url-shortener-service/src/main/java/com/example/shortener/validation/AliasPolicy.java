package com.example.shortener.validation;

import com.example.shortener.code.ShortCodeCodec;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rules for user-chosen aliases.
 *
 * <p>Generated codes are always exactly 7 alphanumeric characters. Custom aliases are not allowed to look
 * like that, so a custom alias can never take a code the generator will produce later. That keeps the two
 * namespaces separate without any lookup.
 */
public final class AliasPolicy {

    private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9_-]{3,64}$");
    private static final Set<String> RESERVED = Set.of(
            "api", "actuator", "admin", "health", "login", "logout", "static", "assets",
            "favicon", "robots", "metrics", "docs", "swagger", "openapi");

    private AliasPolicy() {
    }

    public static void validate(String alias) {
        if (alias == null || !ALLOWED.matcher(alias).matches()) {
            throw new InvalidUrlException("Alias must be 3-64 characters of letters, digits, '-' or '_'");
        }
        if (RESERVED.contains(alias.toLowerCase(Locale.ROOT))) {
            throw new InvalidUrlException("Alias '" + alias + "' is reserved");
        }
        if (ShortCodeCodec.looksGenerated(alias)) {
            throw new InvalidUrlException(
                    "Aliases of exactly " + ShortCodeCodec.CODE_LENGTH
                            + " letters/digits are reserved for generated codes; add '-' or '_' or change the length");
        }
    }
}
