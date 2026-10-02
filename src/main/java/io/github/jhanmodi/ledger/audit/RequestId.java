package io.github.jhanmodi.ledger.audit;

import java.util.Objects;
import java.util.UUID;

/**
 * The id the server gives each HTTP request (ADR-0021). It's on every log line written while serving the request, in
 * the {@code X-Request-Id} response header, and on the audit row, so one id connects a client's report to everything
 * the request did.
 */
public record RequestId(UUID value) {

    public RequestId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
