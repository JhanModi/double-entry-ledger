-- Money movement and the audit log (M4b). See ADR-0018 (transfers and funding), ADR-0019 (interim idempotency),
-- ADR-0020 (audit log), and ADR-0021 (request ids). As in V2, every rule here is also checked in Java; these are the
-- backstops that hold even if the Java code has a bug or someone writes SQL by hand.

-- ---------------------------------------------------------------------------------------------------------------------
-- System accounts with a purpose
-- ---------------------------------------------------------------------------------------------------------------------

-- Some system accounts have a job the code must find them by, such as "the bank-settlement account for USD". Code
-- looks them up by purpose and currency, never by a hard-coded id. Other system accounts (e.g. in tests) have none.
ALTER TABLE accounts ADD COLUMN purpose TEXT;
-- Named so later migrations can replace it as new purposes are introduced (fees, FX pools, ...).
ALTER TABLE accounts ADD CONSTRAINT accounts_purpose_known
    CHECK (purpose IN ('BANK_SETTLEMENT'));
ALTER TABLE accounts ADD CONSTRAINT accounts_purpose_only_on_system_accounts
    CHECK (purpose IS NULL OR kind = 'SYSTEM');
-- The bank-settlement account is the cash the platform holds at its bank: an asset.
ALTER TABLE accounts ADD CONSTRAINT accounts_bank_settlement_is_asset
    CHECK (purpose IS DISTINCT FROM 'BANK_SETTLEMENT' OR type = 'ASSET');
-- At most one account per purpose and currency. Rows without a purpose don't count: NULLs never conflict in UNIQUE.
ALTER TABLE accounts ADD CONSTRAINT accounts_purpose_currency_key
    UNIQUE (purpose, currency);

-- Target for the composite foreign keys below, which tie a movement to an account of the same client and currency.
-- (id alone is already unique, so this never rejects anything on its own.)
ALTER TABLE accounts ADD CONSTRAINT accounts_id_client_currency_key
    UNIQUE (id, client_id, currency);

-- What an account is never changes, and that now includes its purpose.
CREATE OR REPLACE FUNCTION accounts_reject_identity_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.kind <> OLD.kind OR NEW.type <> OLD.type OR NEW.normal_side <> OLD.normal_side
            OR NEW.currency <> OLD.currency OR NEW.created_at <> OLD.created_at
            OR NEW.client_id IS DISTINCT FROM OLD.client_id
            OR NEW.purpose IS DISTINCT FROM OLD.purpose THEN
        RAISE EXCEPTION 'UPDATE on accounts is not allowed: id, kind, type, normal side, currency, owner, purpose, and creation time never change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

-- One bank-settlement account per supported currency. A migration that adds a currency must add its settlement
-- account too (LedgerSchemaIT fails otherwise).
INSERT INTO accounts (kind, type, normal_side, currency, purpose)
SELECT 'SYSTEM', 'ASSET', 'DEBIT', code, 'BANK_SETTLEMENT'
FROM currencies
ORDER BY code;

-- ---------------------------------------------------------------------------------------------------------------------
-- Transfers and fundings: the business record of each movement, beside the ledger transaction that moved the money
-- ---------------------------------------------------------------------------------------------------------------------

CREATE TABLE transfers (
    id                     UUID        PRIMARY KEY DEFAULT uuidv7(),
    client_id              UUID        NOT NULL REFERENCES api_clients (id),
    -- Chosen by the client. Unique per client, so a retry can never move the money twice (ADR-0019).
    idempotency_key        TEXT        NOT NULL,
    source_account_id      UUID        NOT NULL,
    destination_account_id UUID        NOT NULL,
    currency               TEXT        NOT NULL,
    amount                 BIGINT      NOT NULL CHECK (amount > 0),
    description            TEXT,
    -- The ledger transaction that moved the money. Each one backs at most one transfer.
    ledger_transaction_id  UUID        NOT NULL UNIQUE REFERENCES ledger_transactions (id),
    -- The server-generated id of the request that created it (ADR-0021).
    request_id             UUID        NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Both accounts must belong to this transfer's client and be in its currency. A system account has no client, so
    -- it can never match.
    CONSTRAINT transfers_source_account_fkey
        FOREIGN KEY (source_account_id, client_id, currency) REFERENCES accounts (id, client_id, currency),
    CONSTRAINT transfers_destination_account_fkey
        FOREIGN KEY (destination_account_id, client_id, currency) REFERENCES accounts (id, client_id, currency),
    CONSTRAINT transfers_distinct_accounts
        CHECK (source_account_id <> destination_account_id),
    CONSTRAINT transfers_idempotency_key_format
        CHECK (idempotency_key ~ '^[A-Za-z0-9_.:-]{1,255}$'),
    CONSTRAINT transfers_client_idempotency_key_key
        UNIQUE (client_id, idempotency_key),
    -- Matches ledger_transactions_description_length: the description is copied onto the ledger transaction.
    CONSTRAINT transfers_description_length
        CHECK (char_length(description) <= 500)
);

CREATE TABLE fundings (
    id                    UUID        PRIMARY KEY DEFAULT uuidv7(),
    client_id             UUID        NOT NULL REFERENCES api_clients (id),
    idempotency_key       TEXT        NOT NULL,
    account_id            UUID        NOT NULL,
    currency              TEXT        NOT NULL,
    amount                BIGINT      NOT NULL CHECK (amount > 0),
    -- The bank's reference for the deposit this funding stands in for, kept for reconciliation (M11).
    external_reference    TEXT        NOT NULL,
    ledger_transaction_id UUID        NOT NULL UNIQUE REFERENCES ledger_transactions (id),
    request_id            UUID        NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Funding only ever credits the client's own account (ADR-0018, D1).
    CONSTRAINT fundings_account_fkey
        FOREIGN KEY (account_id, client_id, currency) REFERENCES accounts (id, client_id, currency),
    CONSTRAINT fundings_idempotency_key_format
        CHECK (idempotency_key ~ '^[A-Za-z0-9_.:-]{1,255}$'),
    CONSTRAINT fundings_client_idempotency_key_key
        UNIQUE (client_id, idempotency_key),
    CONSTRAINT fundings_external_reference_length
        CHECK (char_length(external_reference) BETWEEN 1 AND 100)
);

-- ---------------------------------------------------------------------------------------------------------------------
-- The audit log (ADR-0020)
-- ---------------------------------------------------------------------------------------------------------------------

-- Target for audit_log_actor_key_fkey below. (key_id alone is already unique, so this never rejects anything on its
-- own.)
ALTER TABLE api_keys ADD CONSTRAINT api_keys_key_id_client_id_key
    UNIQUE (key_id, client_id);

CREATE TABLE audit_log (
    id              UUID        PRIMARY KEY DEFAULT uuidv7(),
    -- Database time at the start of the transaction, the same clock as every created_at.
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Named so later migrations can replace it as new actions are audited.
    action          TEXT        NOT NULL
        CONSTRAINT audit_log_action_known
            CHECK (action IN ('ACCOUNT_OPENED', 'TRANSFER_CREATED', 'FUNDING_CREATED', 'CLIENT_CREATED', 'API_KEY_ISSUED')),
    -- The target's public identifier: a UUID for accounts, transfers, fundings, and clients; the key id for API keys.
    target_id       TEXT        NOT NULL CHECK (char_length(target_id) BETWEEN 1 AND 100),
    -- API_KEY or OPERATOR_CLI; audit_log_actor_details below allows exactly these two.
    actor_type      TEXT        NOT NULL,
    actor_client_id UUID,
    -- The public key id (never the secret), so a leaked key's actions can be listed.
    actor_key_id    TEXT,
    request_id      UUID,
    source_ip       INET,

    -- An API key acts for its client, in a request, from an address. The operator at the command line has none of
    -- these.
    CONSTRAINT audit_log_actor_details
        CHECK ((actor_type = 'API_KEY'
                    AND actor_client_id IS NOT NULL AND actor_key_id IS NOT NULL
                    AND request_id IS NOT NULL AND source_ip IS NOT NULL)
            OR (actor_type = 'OPERATOR_CLI'
                    AND actor_client_id IS NULL AND actor_key_id IS NULL
                    AND request_id IS NULL AND source_ip IS NULL)),
    -- The key must be one of the stated client's keys.
    CONSTRAINT audit_log_actor_key_fkey
        FOREIGN KEY (actor_key_id, actor_client_id) REFERENCES api_keys (key_id, client_id)
);

-- ---------------------------------------------------------------------------------------------------------------------
-- Append-only, for every role (ledger_reject comes from V2)
-- ---------------------------------------------------------------------------------------------------------------------

CREATE TRIGGER transfers_append_only
    BEFORE UPDATE OR DELETE ON transfers
    FOR EACH ROW EXECUTE FUNCTION ledger_reject('money movements are append-only');
CREATE TRIGGER transfers_no_truncate
    BEFORE TRUNCATE ON transfers
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject('money movements are append-only');
CREATE TRIGGER fundings_append_only
    BEFORE UPDATE OR DELETE ON fundings
    FOR EACH ROW EXECUTE FUNCTION ledger_reject('money movements are append-only');
CREATE TRIGGER fundings_no_truncate
    BEFORE TRUNCATE ON fundings
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject('money movements are append-only');
CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION ledger_reject('the audit log is append-only');
CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject('the audit log is append-only');

-- ---------------------------------------------------------------------------------------------------------------------
-- The application's privileges (ADR-0015)
-- ---------------------------------------------------------------------------------------------------------------------

GRANT SELECT, INSERT ON transfers TO ledger_app;
GRANT SELECT, INSERT ON fundings TO ledger_app;
-- The app writes the audit log but can't read, change, or delete it.
GRANT INSERT ON audit_log TO ledger_app;
