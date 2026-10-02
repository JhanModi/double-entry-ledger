package io.github.jhanmodi.ledger;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The application's clock. Code asks for a {@link Clock} instead of calling {@code Instant.now()}, so tests can control time. */
@Configuration(proxyBeanMethods = false)
class TimeConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
