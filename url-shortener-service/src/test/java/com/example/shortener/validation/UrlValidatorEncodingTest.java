package com.example.shortener.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for the SSRF bypass via alternative IPv4 encodings. Every rejected URL below is an internal
 * address (127.0.0.1, 10.0.0.1 or 192.168.0.1) to a browser or HTTP client, even though it doesn't look like one.
 */
class UrlValidatorEncodingTest {

    private final UrlValidator validator = new UrlValidator("sho.rt", false);

    @Test
    void rejectsAlternativeIpEncodings() {
        for (String url : List.of(
                "http://2130706433/",        // decimal 127.0.0.1
                "http://0x7f000001/",        // hex 127.0.0.1
                "http://0177.0.0.1/",        // octal 127.0.0.1
                "http://017700000001/",      // octal, single number
                "http://0x0a000001/",        // hex 10.0.0.1
                "http://3232235521/",        // decimal 192.168.0.1
                "http://127.000.000.001/")) { // zero-padded
            assertThatThrownBy(() -> validator.validate(url)).as(url).isInstanceOf(InvalidUrlException.class);
        }
    }

    @Test
    void stillAcceptsCanonicalPublicIpsAndHostnames() {
        for (String url : List.of("http://8.8.8.8/", "https://93.184.216.34/", "https://example.com/",
                "https://1password.com/", "https://123.example.org/")) {
            assertThat(validator.validate(url)).as(url).isEqualTo(url);
        }
    }
}
