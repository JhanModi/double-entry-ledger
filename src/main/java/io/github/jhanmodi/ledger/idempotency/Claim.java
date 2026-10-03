package io.github.jhanmodi.ledger.idempotency;

/** What {@link IdempotencyKeys#claim} found (ADR-0023). */
public enum Claim {

    /**
     * The key was free, and now belongs to this request until its transaction ends. Carry the request out: the claim
     * commits with it, or rolls back with it and frees the key again.
     */
    NEW,

    /**
     * An earlier request with this key, the same operation, and the same fingerprint was carried out and committed.
     * Replay its result, and do nothing else.
     */
    REPEAT
}
