package com.example.shortener.analytics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyticsHelpersTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/120.0 Safari/537.36 Edg/120.0 | Edge",
            "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/120.0 Safari/537.36 OPR/105 | Opera",
            "Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0 | Firefox",
            "Mozilla/5.0 (Macintosh) AppleWebKit/537.36 Chrome/120.0 Safari/537.36 | Chrome",
            "Mozilla/5.0 (iPhone) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1 | Safari",
            "Googlebot/2.1 (+http://www.google.com/bot.html) | Bot",
            "curl/8.4.0 | CLI/Library",
            "SomethingElse/1.0 | Other"
    })
    void classifiesUserAgents(String ua, String family) {
        assertThat(UserAgentClassifier.classify(ua)).isEqualTo(family);
    }

    @Test
    void unknownUserAgent() {
        assertThat(UserAgentClassifier.classify(null)).isEqualTo("Unknown");
        assertThat(UserAgentClassifier.classify(" ")).isEqualTo("Unknown");
    }

    @Test
    void referrerKeepsOnlyTheHost() {
        assertThat(ReferrerParser.host("https://News.Example.com/article?id=5&email=a@b.c")).isEqualTo("news.example.com");
        assertThat(ReferrerParser.host(null)).isEqualTo(ReferrerParser.DIRECT);
        assertThat(ReferrerParser.host("")).isEqualTo(ReferrerParser.DIRECT);
        assertThat(ReferrerParser.host("not a url")).isEqualTo(ReferrerParser.INVALID);
        assertThat(ReferrerParser.host("android-app://com.slack")).isEqualTo("com.slack");
    }

    @Test
    void ipHashIsStableSaltedAndNotReversible() {
        IpHasher a = new IpHasher("salt-a");
        IpHasher b = new IpHasher("salt-b");
        String h = a.hash("203.0.113.7");
        assertThat(h).hasSize(32).doesNotContain("203");
        assertThat(a.hash("203.0.113.7")).isEqualTo(h);
        assertThat(b.hash("203.0.113.7")).isNotEqualTo(h);
        assertThat(a.hash(null)).isEqualTo("unknown");
        assertThatThrownBy(() -> new IpHasher(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
