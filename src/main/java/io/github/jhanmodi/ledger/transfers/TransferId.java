package io.github.jhanmodi.ledger.transfers;

import java.util.Objects;
import java.util.UUID;

/** Identifies a transfer. */
public record TransferId(UUID value) {

    public TransferId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
