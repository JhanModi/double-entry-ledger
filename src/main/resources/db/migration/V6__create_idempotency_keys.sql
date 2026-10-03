-- Idempotency keys (M6, ADR-0023, building on ADR-0007). Each row is a claim: "this client's request with this key is
-- being, or has been, carried out". The claim is inserted as the first statement of the operation's own transaction,
-- so it commits with the money movement or rolls back with it. As in V2 and V5, every rule here is also checked in
-- Java; these are the backstops that hold even if the Java code has a bug or someone writes SQL by hand.

CREATE TABLE idempotency_keys (
    client_id       UUID        NOT NULL REFERENCES api_clients (id),
    -- Chosen by the client. One key space per client, across every operation.
    idempotency_key TEXT        NOT NULL,
    -- Which operation used the key. A key reused for a different operation is refused.
    operation       TEXT        NOT NULL,
    -- SHA-256 of the validated request (the request fingerprint). A key reused with a different request is refused.
    request_hash    BYTEA       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- After this, the cleanup job may delete the claim. Until it does, the claim is honoured.
    expires_at      TIMESTAMPTZ NOT NULL,

    CONSTRAINT idempotency_keys_pkey
        PRIMARY KEY (client_id, idempotency_key),
    -- The same rule as the Idempotency-Key header and the transfers and fundings tables.
    CONSTRAINT idempotency_keys_key_format
        CHECK (idempotency_key ~ '^[A-Za-z0-9_.:-]{1,255}$'),
    -- Named so later migrations can replace it as new operations take keys (payments, reversals, ...).
    CONSTRAINT idempotency_keys_operation_known
        CHECK (operation IN ('TRANSFER', 'FUNDING')),
    CONSTRAINT idempotency_keys_request_hash_is_sha256
        CHECK (octet_length(request_hash) = 32),
    CONSTRAINT idempotency_keys_expire_after_creation
        CHECK (expires_at > created_at)
);

-- The cleanup job looks for expired claims, oldest first.
CREATE INDEX idempotency_keys_expires_at_idx ON idempotency_keys (expires_at);

-- A claim never changes: a different request needs a different key. Expired claims are deleted, so DELETE stays
-- allowed. Like every trigger, this catches mistakes by any role; it doesn't stop an owner who disables it on purpose.
CREATE TRIGGER idempotency_keys_never_change
    BEFORE UPDATE ON idempotency_keys
    FOR EACH ROW EXECUTE FUNCTION ledger_reject('a claimed idempotency key never changes');

-- The application claims keys, reads them back, and deletes expired ones. It can never change one (ADR-0015).
GRANT SELECT, INSERT, DELETE ON idempotency_keys TO ledger_app;
