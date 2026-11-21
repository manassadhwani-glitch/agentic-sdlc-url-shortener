package com.example.shortener.validation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AliasPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"abc", "my-link", "promo_2026", "launch2026x", "A-B"})
    void acceptsValidAliases(String alias) {
        assertThatCode(() -> AliasPolicy.validate(alias)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "has space", "emoji😀", "slash/x", "api", "ADMIN", "Actuator"})
    void rejectsMalformedOrReservedAliases(String alias) {
        assertThatThrownBy(() -> AliasPolicy.validate(alias)).isInstanceOf(InvalidUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc1234", "ZZZZZZZ", "0000000"})
    void rejectsAliasesInTheGeneratedCodeNamespace(String alias) {
        assertThatThrownBy(() -> AliasPolicy.validate(alias))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("reserved for generated codes");
    }
}
