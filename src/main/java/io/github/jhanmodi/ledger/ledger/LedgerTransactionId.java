package io.github.jhanmodi.ledger.ledger;

import java.util.Objects;
import java.util.UUID;

/** Identifies a ledger transaction. */
public record LedgerTransactionId(UUID value) {

    public LedgerTransactionId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
