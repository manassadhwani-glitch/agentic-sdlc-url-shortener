package com.example.shortener.config;

import com.example.shortener.code.ShortCodeCodec;
import com.example.shortener.validation.UrlValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Clock;

@Configuration
public class AppConfig {

    /** Injected everywhere time is needed, so tests can control it. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ShortCodeCodec shortCodeCodec(ShortenerProperties props) {
        return new ShortCodeCodec(Long.decode(props.codeSecret()));
    }

    @Bean
    public UrlValidator urlValidator(ShortenerProperties props) {
        String selfHost = URI.create(props.normalizedBaseUrl()).getHost();
        return new UrlValidator(selfHost, props.validation().resolveDns());
    }
}
