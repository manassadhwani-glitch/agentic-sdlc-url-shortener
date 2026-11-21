package com.example.shortener.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeysTest {

    private final ApiKeys keys = new ApiKeys(List.of("alpha", "beta"));

    @Test
    void acceptsConfiguredKeysOnly() {
        assertThat(keys.isValid("alpha")).isTrue();
        assertThat(keys.isValid("beta")).isTrue();
        assertThat(keys.isValid("gamma")).isFalse();
        assertThat(keys.isValid("")).isFalse();
        assertThat(keys.isValid(null)).isFalse();
    }

    @Test
    void keyIdIsStableShortAndDoesNotContainTheKey() {
        assertThat(ApiKeys.keyId("alpha")).hasSize(16).isEqualTo(ApiKeys.keyId("alpha")).doesNotContain("alpha");
        assertThat(ApiKeys.keyId("alpha")).isNotEqualTo(ApiKeys.keyId("beta"));
    }

    @Test
    void requiresAtLeastOneKey() {
        assertThatThrownBy(() -> new ApiKeys(List.of(" "))).isInstanceOf(IllegalArgumentException.class);
    }
}
