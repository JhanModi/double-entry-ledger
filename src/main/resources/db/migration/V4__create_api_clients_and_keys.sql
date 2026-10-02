-- API clients (tenants), their API keys, and account ownership. See ADR-0011, ADR-0016, and ADR-0017.

CREATE TABLE api_clients (
    id         UUID        PRIMARY KEY DEFAULT uuidv7(),
    name       TEXT        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    status     TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE api_keys (
    id          UUID        PRIMARY KEY DEFAULT uuidv7(),
    client_id   UUID        NOT NULL REFERENCES api_clients (id),
    -- The public half of the key (dbl_<key_id>_<secret>), used to find this row.
    key_id      TEXT        NOT NULL UNIQUE CHECK (key_id ~ '^[0-9a-f]{16}$'),
    -- SHA-256 of the secret half. The secret itself is never stored (ADR-0011).
    secret_hash BYTEA       NOT NULL CHECK (octet_length(secret_hash) = 32),
    scopes      TEXT[]      NOT NULL CHECK (cardinality(scopes) > 0 AND scopes <@ ARRAY['read', 'write', 'admin']),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ
);

CREATE INDEX api_keys_client_id_idx ON api_keys (client_id);

-- Every customer account belongs to exactly one client; system accounts belong to none.
-- (Adding this constraint would fail if customer accounts without a client existed. None do before this migration.)
ALTER TABLE accounts ADD COLUMN client_id UUID REFERENCES api_clients (id);
ALTER TABLE accounts ADD CONSTRAINT accounts_customer_has_client
    CHECK ((kind = 'CUSTOMER') = (client_id IS NOT NULL));
CREATE INDEX accounts_client_id_idx ON accounts (client_id);

-- An account can never change owner, so the identity trigger from V2 now covers client_id too. It uses
-- IS DISTINCT FROM because client_id can be NULL, and NULL <> NULL is never true.
CREATE OR REPLACE FUNCTION accounts_reject_identity_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.kind <> OLD.kind OR NEW.type <> OLD.type OR NEW.normal_side <> OLD.normal_side
            OR NEW.currency <> OLD.currency OR NEW.created_at <> OLD.created_at
            OR NEW.client_id IS DISTINCT FROM OLD.client_id THEN
        RAISE EXCEPTION 'UPDATE on accounts is not allowed: id, kind, type, normal side, currency, owner, and creation time never change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

-- The application's privileges on the new tables (ADR-0015). Revoking a key is the only update it may make.
GRANT SELECT, INSERT ON api_clients TO ledger_app;
GRANT SELECT, INSERT ON api_keys TO ledger_app;
GRANT UPDATE (revoked_at) ON api_keys TO ledger_app;
