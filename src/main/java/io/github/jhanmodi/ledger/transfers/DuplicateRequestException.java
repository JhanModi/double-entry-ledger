package io.github.jhanmodi.ledger.transfers;

import java.util.Objects;
import java.util.UUID;

/**
 * The client already used this idempotency key, so this request was not applied again (ADR-0019). Carries the id of
 * the original transfer or funding, so the client can look up what happened. Until M6, this is what a retry gets
 * instead of a replay of the original response.
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
