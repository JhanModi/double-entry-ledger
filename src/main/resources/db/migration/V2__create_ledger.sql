-- Ledger core: accounts, ledger transactions, and entries.
-- See ADR-0003 (entry format), ADR-0004 (cached balances), ADR-0005 (overdraft backstop), ADR-0014 (UUIDv7 ids),
-- and docs/design.md section 4. Every rule here is also checked in Java; these are the backstops that hold even
-- if the Java code has a bug or someone writes SQL by hand.

CREATE TABLE accounts (
    id             UUID        PRIMARY KEY DEFAULT uuidv7(),
    kind           TEXT        NOT NULL CHECK (kind IN ('CUSTOMER', 'SYSTEM')),
    type           TEXT        NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    normal_side    TEXT        NOT NULL CHECK (normal_side IN ('DEBIT', 'CREDIT')),
    currency       TEXT        NOT NULL REFERENCES currencies (code),
    status         TEXT        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    posted_balance BIGINT,
    held_balance   BIGINT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Assets and expenses increase with debits; liabilities, equity, and revenue increase with credits.
    CONSTRAINT accounts_normal_side_matches_type
        CHECK ((type IN ('ASSET', 'EXPENSE')) = (normal_side = 'DEBIT')),
    -- A customer's wallet is money the platform owes that customer.
    CONSTRAINT accounts_customer_is_liability
        CHECK (kind <> 'CUSTOMER' OR type = 'LIABILITY'),
    -- Only customer accounts cache balances. System balances are derived from entries, so their columns stay NULL
    -- and can never be mistaken for a real balance.
    CONSTRAINT accounts_only_customers_cache_balances
        CHECK ((kind = 'CUSTOMER' AND posted_balance IS NOT NULL AND held_balance IS NOT NULL)
            OR (kind = 'SYSTEM' AND posted_balance IS NULL AND held_balance IS NULL)),
    CONSTRAINT accounts_held_balance_non_negative
        CHECK (held_balance >= 0),
    -- The overdraft backstop. For system accounts both columns are NULL, so the check passes: they may go negative.
    CONSTRAINT accounts_available_balance_non_negative
        CHECK (posted_balance - held_balance >= 0),
    -- Target for the composite foreign key on entries, which forces an entry's currency to match its account's.
    CONSTRAINT accounts_id_currency_key
        UNIQUE (id, currency)
);

CREATE TABLE ledger_transactions (
    id             UUID        PRIMARY KEY DEFAULT uuidv7(),
    type           TEXT        NOT NULL,
    description    TEXT,
    -- The business date the transaction belongs to, separate from the moment it was recorded.
    effective_date DATE        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Named so later migrations can replace it as new transaction types are introduced.
    CONSTRAINT ledger_transactions_type_known
        CHECK (type IN ('TRANSFER', 'FUNDING')),
    CONSTRAINT ledger_transactions_description_length
        CHECK (char_length(description) <= 500)
);

CREATE TABLE entries (
    id             UUID        PRIMARY KEY DEFAULT uuidv7(),
    transaction_id UUID        NOT NULL REFERENCES ledger_transactions (id),
    account_id     UUID        NOT NULL,
    currency       TEXT        NOT NULL,
    direction      TEXT        NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount         BIGINT      NOT NULL CHECK (amount > 0),

    CONSTRAINT entries_account_currency_fkey
        FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency)
);

-- An account's history, newest first, and deriving its balance.
CREATE INDEX entries_account_id_id_idx ON entries (account_id, id);
-- Finding a transaction's entries when checking that it balances.
CREATE INDEX entries_transaction_id_idx ON entries (transaction_id);

-- A ledger transaction must have at least two entries, and in every currency its debits must equal its credits.
CREATE FUNCTION ledger_assert_transaction_balanced(txn_id UUID) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    entry_count         BIGINT;
    unbalanced_currency TEXT;
BEGIN
    SELECT count(*) INTO entry_count FROM entries WHERE transaction_id = txn_id;
    IF entry_count < 2 THEN
        RAISE EXCEPTION 'ledger transaction % has % entries; at least 2 are required', txn_id, entry_count
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT currency INTO unbalanced_currency
    FROM entries
    WHERE transaction_id = txn_id
    GROUP BY currency
    HAVING coalesce(sum(amount) FILTER (WHERE direction = 'DEBIT'), 0)
        <> coalesce(sum(amount) FILTER (WHERE direction = 'CREDIT'), 0)
    LIMIT 1;
    IF unbalanced_currency IS NOT NULL THEN
        RAISE EXCEPTION 'ledger transaction % is not balanced in %', txn_id, unbalanced_currency
            USING ERRCODE = 'check_violation';
    END IF;
END $$;

CREATE FUNCTION ledger_entries_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM ledger_assert_transaction_balanced(NEW.transaction_id);
    RETURN NULL;
END $$;

CREATE FUNCTION ledger_transactions_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM ledger_assert_transaction_balanced(NEW.id);
    RETURN NULL;
END $$;

-- Checked at COMMIT (DEFERRABLE INITIALLY DEFERRED), not after each INSERT: entries arrive one at a time, and a
-- transaction is only balanced once all of them are in.
CREATE CONSTRAINT TRIGGER entries_balanced
    AFTER INSERT ON entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_balanced();

-- The trigger above only fires when an entry is inserted, so on its own it would let a transaction with zero
-- entries through. This one fires for every new ledger transaction.
CREATE CONSTRAINT TRIGGER ledger_transactions_balanced
    AFTER INSERT ON ledger_transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_transactions_balanced();

-- Rejects the operation outright. Each trigger passes the reason as its argument (read here as TG_ARGV[0]).
CREATE FUNCTION ledger_reject() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: %', TG_OP, TG_TABLE_NAME, TG_ARGV[0]
        USING ERRCODE = 'restrict_violation';
END $$;

-- Ledger records are append-only. A mistake is corrected with a new, reversing transaction (M8), never an edit.
CREATE TRIGGER entries_append_only
    BEFORE UPDATE OR DELETE ON entries
    FOR EACH ROW EXECUTE FUNCTION ledger_reject('ledger records are append-only');
CREATE TRIGGER entries_no_truncate
    BEFORE TRUNCATE ON entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject('ledger records are append-only');
CREATE TRIGGER ledger_transactions_append_only
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_reject('ledger records are append-only');
CREATE TRIGGER ledger_transactions_no_truncate
    BEFORE TRUNCATE ON ledger_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject('ledger records are append-only');

-- Accounts are closed, never deleted, and what an account *is* never changes. Only balances and status may.
CREATE FUNCTION accounts_reject_identity_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.kind <> OLD.kind OR NEW.type <> OLD.type OR NEW.normal_side <> OLD.normal_side
            OR NEW.currency <> OLD.currency OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'UPDATE on accounts is not allowed: id, kind, type, normal side, currency, and creation time never change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER accounts_identity_immutable
    BEFORE UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION accounts_reject_identity_change();
CREATE TRIGGER accounts_never_deleted
    BEFORE DELETE ON accounts
    FOR EACH ROW EXECUTE FUNCTION ledger_reject('accounts are closed, never deleted');
CREATE TRIGGER accounts_no_truncate
    BEFORE TRUNCATE ON accounts
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject('accounts are closed, never deleted');
