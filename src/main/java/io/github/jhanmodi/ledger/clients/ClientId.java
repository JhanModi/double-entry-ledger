package io.github.jhanmodi.ledger.clients;

import java.util.Objects;
import java.util.UUID;

/** Identifies an API client: a business that uses the API (a tenant). */
public record ClientId(UUID value) {

    public ClientId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
