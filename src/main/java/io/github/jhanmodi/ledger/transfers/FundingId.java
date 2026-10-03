package io.github.jhanmodi.ledger.transfers;

import java.util.Objects;
import java.util.UUID;

/** Identifies a funding. */
public record FundingId(UUID value) {

    public FundingId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
