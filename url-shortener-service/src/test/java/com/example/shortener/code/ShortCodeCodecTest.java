package com.example.shortener.code;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShortCodeCodecTest {

    private final ShortCodeCodec codec = new ShortCodeCodec(0x1F3A5C7E9B2DL);

    @Test
    void base62RoundTrips() {
        for (long v : new long[]{0, 1, 61, 62, 3843, 123_456_789L, Long.MAX_VALUE}) {
            assertThat(Base62.decode(Base62.encode(v))).isEqualTo(v);
        }
        assertThat(Base62.encode(61)).isEqualTo("z");
        assertThat(Base62.encode(5, 3)).isEqualTo("005");
    }

    @Test
    void base62RejectsBadInput() {
        assertThatThrownBy(() -> Base62.encode(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base62.decode("ab!")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base62.encode(62L * 62 * 62, 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void codesAreFixedLengthAndUnique() {
        Set<String> seen = new HashSet<>();
        for (long id = 100_000; id < 200_000; id++) {
            String code = codec.encode(id);
            assertThat(code).hasSize(ShortCodeCodec.CODE_LENGTH);
            assertThat(ShortCodeCodec.looksGenerated(code)).isTrue();
            assertThat(seen.add(code)).as("duplicate for id %d", id).isTrue();
        }
    }

    @Test
    void consecutiveIdsDoNotProduceAdjacentCodes() {
        String a = codec.encode(100_000);
        String b = codec.encode(100_001);
        assertThat(a.substring(0, 5)).isNotEqualTo(b.substring(0, 5));
    }

    @Test
    void differentSecretsGiveDifferentCodes() {
        assertThat(new ShortCodeCodec(1).encode(42)).isNotEqualTo(new ShortCodeCodec(2).encode(42));
    }

    @Test
    void rejectsIdsOutsideTheCodeSpace() {
        assertThatThrownBy(() -> codec.encode(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.encode(1L << 41)).isInstanceOf(IllegalArgumentException.class);
        assertThat(codec.encode((1L << 41) - 1)).hasSize(7);
    }
}
