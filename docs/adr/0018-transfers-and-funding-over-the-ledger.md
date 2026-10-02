# ADR-0018: Transfers and funding as business records over the ledger

- **Status:** Accepted
- **Date:** 2026-10-02

## Context
Since M3a the ledger can post balanced transactions, but API clients can't move money yet. M4b adds two kinds of movement:
- **Transfers:** a client moves money between two of its own customer accounts.
- **Funding:** money enters a customer account. Real inbound bank payments arrive in M9. Until then, funding stands in for them.

A ledger transaction records *what* moved. It doesn't record who asked, with which key, in which request, under which idempotency key, or against which bank reference. Those facts are needed for tracing, idempotency (ADR-0019), and reconciliation (M11).

## Options considered
- **Where the business record lives**
  - *Add client and request columns to `ledger_transactions`.* This couples the generic ledger to the API: every posting would need them, including internal ones that no client made.
  - *A table per kind of movement* (`transfers`, `fundings`), each pointing to its ledger transaction. The ledger stays generic, and each table has exactly the columns that fit it.
  - *One generic `money_movements` table.* It would need nullable columns and a CHECK per kind of movement.
- **Tenant isolation for movements:** a Java ownership check only, or a Java check plus a database backstop.
- **Who may fund (D1)**
  - *An `admin` key, for its own client's accounts.*
  - *An `admin` key, for any client's account* (a platform operator). Today the CLI can grant `admin` to any client, so this would break tenant isolation without a separate operator identity.
  - *A CLI command only,* with no HTTP endpoint.
- **How code finds the bank-settlement account (D2):** a `purpose` column on `accounts`, a separate lookup table, creating it at startup (two instances could race), or hard-coded UUIDs (magic constants).
- **Maximum amount (D4):** the same number of major units in every currency, a fixed limit per currency, or limits read from the environment.

## Decision
- **`transfers` and `fundings` tables (V5).**
  - Each row points to exactly one ledger transaction (`UNIQUE`).
  - Each row is written in the same database transaction as its posting.
  - Both tables are append-only (triggers), and the app may only `SELECT` and `INSERT`.
- **A database backstop for ownership and currency:**
  - Composite foreign keys `(account_id, client_id, currency) → accounts (id, client_id, currency)`. A row can only reference an account that belongs to the same client and is in the movement's currency.
  - System accounts have no `client_id`, so they can never match.
  - `accounts` gains `UNIQUE (id, client_id, currency)` as the target of these keys.
- **Transfers:**
  - Same-client only: both accounts are looked up with `accountOwnedBy`.
  - The posting debits the source and credits the destination. Customer accounts are liabilities, so the debit lowers the source's balance and the credit raises the destination's.
  - Source and destination must differ (a CHECK, as well as Java).
- **Funding (D1, option A):**
  - Requires the `admin` scope, and targets one of the caller's own accounts.
  - The posting debits the bank-settlement account for that currency and credits the customer: the bank holds more cash, and the platform owes the customer more.
  - Requires an external reference (the bank's reference for the deposit), kept for reconciliation.
  - Cross-tenant operator actions are an open decision for M8.
- **Settlement accounts (D2, option A):**
  - `accounts.purpose` is set only on system accounts. Its only value today is `BANK_SETTLEMENT`.
  - `UNIQUE (purpose, currency)`; a bank-settlement account must be an asset; the identity trigger stops `purpose` from changing.
  - V5 creates one bank-settlement account per currency.
  - Code finds the account by purpose and currency. Its id never appears in a request or a response.
- **Amount limits (D4, option B):** a maximum per request in each currency, set to roughly equal real value:

  | Currency | Maximum | In minor units |
  |---|---|---|
  | USD | 1,000,000.00 | 100,000,000 |
  | EUR | 1,000,000.00 | 100,000,000 |
  | JPY | 150,000,000 | 150,000,000 |
  | KWD | 300,000.000 | 300,000,000 |

  - It's a sanity cap against wrong input, not a per-client risk limit. The worked examples are in `docs/design.md`.
  - It's enforced in Java only, because it's policy that may change. The database enforces `amount > 0`.

## Consequences
- **The ledger stays generic.** Payments (M9) and FX (M12) will add their own tables the same way.
- **Tenant isolation now has a database backstop for money movement,** not just for reads.
- **An `admin` key can create customer money with no real deposit behind it.** It's limited by who receives an admin key (only the operator, through the CLI), every funding is audit-logged (ADR-0020), and inbound payments replace it in M9.
- **A transfer row repeats its amount and currency,** and nothing yet checks that they agree with the ledger entries. The invariant checker could do this later.
- **A new currency needs a settlement account** in the same migration. A test fails if one is missing.

## How to explain it
"The ledger records the money. A separate transfers table records the business intent: who asked, with which request, under which idempotency key. Composite foreign keys make the database itself refuse a transfer that touches another client's account or a different currency. Funding stands in for real bank deposits until the payments milestone. It needs an admin key, only works on the caller's own accounts, and is audit-logged."
