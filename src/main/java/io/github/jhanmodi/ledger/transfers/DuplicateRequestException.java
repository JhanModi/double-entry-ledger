package io.github.jhanmodi.ledger.transfers;

import java.util.Objects;
import java.util.UUID;

/**
 * The client used this idempotency key on a transfer or funding whose claim has since expired and been deleted, so it
 * can't be replayed (ADR-0023). The request was not applied again. Carries the original's id, so the client can look up
 * what happened. A retry within the retention period is replayed instead; this is the permanent backstop after it.
 */
public final class DuplicateRequestException extends RuntimeException {

    private final UUID originalId;

    DuplicateRequestException(UUID originalId) {
        super("this idempotency key was already used, by " + originalId);
        this.originalId = Objects.requireNonNull(originalId, "originalId");
    }

    public UUID originalId() {
        return originalId;
    }
}
