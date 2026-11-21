package com.example.shortener.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator("sho.rt", false);

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",
            "http://example.com/path?q=1#frag",
            "https://sub.domain.co.uk:8443/a/b",
            "http://8.8.8.8/",
            "http://[2001:4860:4860::8888]/",
            "https://xn--bcher-kva.example/" // IDN hosts must be sent punycode-encoded
    })
    void acceptsPublicWebUrls(String url) {
        assertThat(validator.validate(url)).isEqualTo(url);
    }

    @Test
    void trimsWhitespace() {
        assertThat(validator.validate("  https://example.com  ")).isEqualTo("https://example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:text/html,hi",
            "file:///etc/passwd",
            "ftp://example.com",
            "example.com",
            "https://",
            "not a url",
            "https://trusted.com@evil.com/",
            "http://localhost:8080/admin",
            "http://api.localhost/",
            "http://printer.local/",
            "http://db.internal/",
            "http://127.0.0.1/",
            "http://10.0.0.5/",
            "http://172.16.3.4/",
            "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data",
            "http://100.64.0.1/",
            "http://0.0.0.0/",
            "http://[::1]/",
            "http://[fd12:3456::1]/",
            "http://[fe80::1]/",
            "https://sho.rt/abc1234"
    })
    void rejectsUnsafeOrMalformedUrls(String url) {
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void rejectsBlankAndOverlongUrls() {
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(InvalidUrlException.class);
        assertThatThrownBy(() -> validator.validate("   ")).isInstanceOf(InvalidUrlException.class);
        String tooLong = "https://example.com/" + "a".repeat(UrlValidator.MAX_LENGTH);
        assertThatThrownBy(() -> validator.validate(tooLong))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("exceeds");
    }
}
