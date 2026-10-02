package io.github.jhanmodi.ledger.web;

import io.github.jhanmodi.ledger.audit.RequestId;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registers {@link RequestIdFilter} (ADR-0021). Only when serving HTTP; command-line mode has no requests. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class RequestIdConfiguration {

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration =
                new FilterRegistrationBean<>(new RequestIdFilter(randomRequestIds()));
        // Ahead of every other filter, Spring Security's included, so even a 401 has an id and its log line carries it.
        // (Spring Boot's character-encoding filter runs at HIGHEST_PRECEDENCE itself, and only touches encodings.)
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    /** Random UUIDs: unique, and they reveal nothing (unlike a counter, which would reveal the request volume). */
    static Supplier<RequestId> randomRequestIds() {
        return () -> new RequestId(UUID.randomUUID());
    }
}
