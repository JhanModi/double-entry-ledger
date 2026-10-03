package io.github.jhanmodi.ledger;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's {@code @Scheduled} methods. The only one so far is the idempotency-key cleanup (ADR-0023), which
 * exists only in the web server, so the command-line mode schedules nothing.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfiguration {}
