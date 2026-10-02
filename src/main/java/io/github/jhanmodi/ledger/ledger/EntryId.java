package io.github.jhanmodi.ledger.ledger;

import java.util.Objects;
import java.util.UUID;

/** Identifies one entry. Also the cursor for paging through an account's history. */
public record EntryId(UUID value) {

    public EntryId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
