# Design: Double-Entry Ledger & Payments API

> **Status:** each section is filled in as its milestone lands (see [roadmap](roadmap.md)). Where a section or table row names a finished milestone, it describes what exists; the rest is design. Public-facing docs describe only what exists.

## 1. Purpose and scope

A backend service that moves money between accounts the way real financial systems do. It records every movement as balanced double-entry postings, never loses or duplicates a payment, and can prove its balances are correct.

**Goals**
- Correctness under retries, concurrency, and crashes, with tests that prove it.
- An auditable, append-only history.
- Clear, explainable design decisions ([ADRs](adr/README.md)).

**Non-goals**
- A user interface. The API is the product.
- Real money or a real bank. The bank is simulated ([ADR-0010](adr/0010-in-process-mock-bank-and-recovery.md)).
- Card data (out of PCI scope), KYC/AML, and human end-user accounts. API clients are businesses.
- Multi-region deployment or horizontal sharding.

## 2. Architecture overview

A modular monolith: one Spring Boot app and one PostgreSQL database ([ADR-0001](adr/0001-modular-monolith.md)).

This sketch is the target, including modules not built yet (payments, fx, outbox, reconciliation, Kafka). Diagrams of what exists today, the transfer flow, and the data model are in [architecture.md](architecture.md).

```
API client ──HTTPS + API key──▶ ┌──────────── Spring Boot app (modular monolith) ────────────┐
                                │ API layer: auth filter → controllers → validation          │
                                │                                                            │
                                │  transfers      payments ──▶ BankRail ──▶ mock bank        │
                                │      │          (holds, state machine, recovery sweeper)   │
                                │      ▼              ▼                                      │
                                │  ledger core: accounts · ledger_transactions · entries     │
                                │  fx (quotes, conversions)   reconciliation (scheduled)     │
                                │  infra: idempotency · outbox relay · audit log · metrics   │
                                └───────────────┬──────────────────────────┬─────────────────┘
                                                ▼                          ▼ (M13)
                                       PostgreSQL (source of truth)   Kafka ──▶ webhook dispatcher
```

## 3. Modules

| Module | Owns | Milestone |
|---|---|---|
| `money` | `Money`, currencies, rounding, allocation | M2 |
| `ledger` | accounts, ledger transactions, entries, balances, invariant checker; account locking, the lock timeout, and deadlock retries (`RetryingTransactions`) | M3a, M3b, M5 |
| `clients` | API clients, API keys, scopes, the `Caller` making a request | M4a |
| `transfers` | transfers and fundings: instant movements, amount limits, replaying a transfer or funding by its key | M4b, M6 |
| `idempotency` | idempotency keys: the claim, request fingerprints, expiry cleanup | M6 |
| `audit` | append-only audit log; request ids | M4b |
| `web` | HTTP: security rules, controllers, request ids, Problem Details | M4a, M4b |
| `payments` | payments, holds, bank instructions, recovery sweeper, `BankRail` | M9a, M9b |
| `outbox` | outbox events, relay, `EventPublisher` | M10 |
| `reconciliation` | statement import, matching, results | M11 |
| `fx` | rates, quotes, conversions | M12 |

**Dependencies run one way** (ArchUnit, `ArchitectureTest`): web → transfers → idempotency → ledger → clients → audit, and money is used by ledger, transfers, and web. A module never depends on one above it, so no cycle can form.

## 4. Data model

Since M3a. Migration: `V2__create_ledger.sql`. Decisions: [ADR-0003](adr/0003-entry-direction-and-positive-amount.md), [ADR-0004](adr/0004-balances-as-cached-projection.md), [ADR-0014](adr/0014-uuidv7-ids.md).

| Table | Holds | Key rules (enforced by the database) |
|---|---|---|
| `currencies` | Supported currencies and exponents | Mirrors the `CurrencyCode` enum (ADR-0013) |
| `accounts` | What each account is, plus cached balances for customers | `normal_side` matches `type`; customers are liabilities; only customers have cached balances; available ≥ 0; identity columns never change; never deleted |
| `ledger_transactions` | One row per posting: type, description, business date | At least 2 entries, and balanced per currency, checked at commit; append-only |
| `entries` | One row per line: account, direction, positive amount | `amount > 0`; currency must match the account's (composite FK); append-only |

All ids are UUIDv7. Customer `posted_balance` is a cache of the entries, updated in the same transaction by SQL delta. System accounts have NULL balance columns, and their balance is derived from entries.

Since M4a (`V4`): `api_clients`, `api_keys` (hash only), and `accounts.client_id`, which every customer account has and no system account has.

Since M4b (`V5__create_transfers_fundings_and_audit_log.sql`; [ADR-0018](adr/0018-transfers-and-funding-over-the-ledger.md) to [ADR-0020](adr/0020-audit-log-in-the-business-transaction.md)):

| Table | Holds | Key rules (enforced by the database) |
|---|---|---|
| `accounts.purpose` | The job of a system account code must find, e.g. `BANK_SETTLEMENT` | Only on system accounts; one per purpose and currency; bank settlement is an asset; never changes. V5 creates one bank-settlement account per currency. |
| `transfers` | One row per transfer: client, idempotency key, both accounts, amount, description, request id | Both accounts belong to the row's client and are in its currency (composite foreign keys); different accounts; `amount > 0`; `UNIQUE (client_id, idempotency_key)`; one row per ledger transaction; append-only |
| `fundings` | One row per funding: client, idempotency key, account, amount, external reference, request id | The account belongs to the row's client and is in its currency; `amount > 0`; reference of 1–100 characters; `UNIQUE (client_id, idempotency_key)`; one row per ledger transaction; append-only |
| `audit_log` | Who did what, to what, when, and from where | An API-key actor has a client, a key of that client, a request id, and an address; the CLI operator has none of these; known actions only; append-only; the app may only INSERT |

A transfer or funding row and its ledger transaction are written in one database transaction. The ledger stays generic: the business record points to it, never the other way round.

Since M6 (`V6__create_idempotency_keys.sql`; [ADR-0023](adr/0023-idempotency-with-claim-first-and-replay.md)):

| Table | Holds | Key rules (enforced by the database) |
|---|---|---|
| `idempotency_keys` | One claim per client and key: the operation that used it, the request's SHA-256 fingerprint, when it was made, and when it expires | `PRIMARY KEY (client_id, idempotency_key)`, so one key space per client across operations; known operations only; the hash is 32 bytes; it expires after it was created; a claim never changes (trigger); the app may SELECT, INSERT, and DELETE, never UPDATE |

A claim is written in the same transaction as the movement it guards, and holds no response: a replay is rebuilt from the transfer or funding row. Expired claims are deleted; the movement rows keep their keys, and their `UNIQUE (client_id, idempotency_key)`, for good.

## 5. Key flows

### 5.1 Transfer (built in M4b, M5, and M6)

**Before the transaction**, each step can answer the request on its own:
1. `RequestIdFilter` gives the request its id (ADR-0021).
2. The key is authenticated (401) and the security rule checked (`write`, 403).
3. The input is validated (400 `invalid-request`): the `Idempotency-Key` header, integer and positive amounts, known fields only, and description length.
4. The command is built: two different accounts and an amount within the limit, or 422.

**In one database transaction** at READ COMMITTED, started by `TransferService` through `RetryingTransactions`:
1. The service checks the `write` scope again (defense in depth).
2. **Claim the idempotency key** ([ADR-0023](adr/0023-idempotency-with-claim-first-and-replay.md)). `IdempotencyKeys.claim` sets the 2-second `lock_timeout`, then inserts a claim with the request's fingerprint, unless this client already has one for the key (`ON CONFLICT DO NOTHING`):
   - **The same request was already carried out** (same operation and fingerprint): read the transfer by its key and **replay** it. Nothing else happens.
   - **A different request used the key:** 422 `idempotency-key-reused`.
   - **Another request holds the key and hasn't committed:** wait for it. If it commits, replay it; if it rolls back, the claim is this request's. If it's still running after 2 seconds, 409 `request-in-progress`.
   - **The key is free:** the claim is this request's, until the transaction ends.

   This comes first, so a retry is recognized even after the money has been spent, and a simultaneous duplicate waits before it touches any account.
3. **Does a transfer already have this key?** Only if its claim expired and was deleted. Then the answer is 409 `duplicate-request` with the original's id, before any money moves.
4. **Look up both accounts owner-scoped:** 404, naming `sourceAccountId` or `destinationAccountId`. Then check that both are in the amount's currency: 422.
5. **Post the ledger transaction:** debit the source and credit the destination. The posting service ([ADR-0005](adr/0005-pessimistic-row-locking.md), [ADR-0022](adr/0022-lock-timeouts-retries-and-the-busy-response.md)):
   1. sets a 2-second `lock_timeout` for the rest of the transaction;
   2. locks both accounts in ascending id order (`FOR NO KEY UPDATE`), waiting its turn if another request holds one;
   3. re-reads their status and balances under the lock, and rejects a closed account or an overdraft (422) before writing anything;
   4. writes the entries and applies each balance change as a SQL delta.

   If another request holds an account for over 2 seconds, the answer is 503 `account-busy`, and nothing has moved.
6. **Insert the transfer row.** Its composite foreign keys re-check ownership and currency, and its unique key is the permanent backstop for the idempotency key.
7. **Write the audit row** (`TRANSFER_CREATED`), then commit. The claim commits with everything else.

**If anything fails** (404, 422, 503), everything rolls back, the claim included, so the key is free and a retry runs again. Only successes are remembered.

**If step 6 hits the unique key:** a duplicate got past the claim, which only a bug in the claim layer could allow. This transaction rolls back, the service looks up the winner, and the answer is 409 `duplicate-request` with its id. `UniqueKeyBackstopIT` proves it with the claim layer switched off.

**If Postgres aborts the transaction to break a deadlock:** `RetryingTransactions` runs it again from step 1, claim included, up to 3 attempts in all, after a short random pause. If every attempt is aborted, the answer is 503 `account-busy`. A lock timeout is never retried ([ADR-0022](adr/0022-lock-timeouts-retries-and-the-busy-response.md)).

**The response:** 201 with `Location` and the transfer. A replay is built from the same transfer row, so it's identical, plus `Idempotent-Replayed: true`.

**M10 adds:** the outbox event in step 7.

### 5.2 Funding (M4b)

Funding has the same shape as a transfer, with these differences ([ADR-0018](adr/0018-transfers-and-funding-over-the-ledger.md)):
- It needs the `admin` scope, both in the security rules and in the service.
- It credits one of the caller's own accounts and debits the bank-settlement account for its currency. That account is found by `purpose`, and its id never appears in the API.
- It records the bank's external reference, and the ledger description is `Deposit <reference>`.
- Its key is claimed as a `FUNDING`. Keys are per client across operations, so a transfer's key can't be reused for a funding (422).

It stands in for inbound bank payments until M9.

### 5.3 Payment lifecycle (M9a–M9b)

| Transition | Ledger effect |
|---|---|
| PENDING → AUTHORIZED | Hold placed, bank instruction QUEUED (same transaction) |
| PENDING → FAILED | No hold existed |
| AUTHORIZED → SETTLED | Hold captured, entries posted |
| AUTHORIZED → FAILED | Hold released. Only on an explicit decline or a safe expiry |
| AUTHORIZED → NEEDS_REVIEW | Hold stays active |
| NEEDS_REVIEW → SETTLED / FAILED | Admin resolution, with reason, audit-logged |
| SETTLED → REVERSED | Reversing entries |

Recovery, retries, and hold expiry: [ADR-0006](adr/0006-holds-table.md), [ADR-0010](adr/0010-in-process-mock-bank-and-recovery.md).

## 6. Invariants

These must hold at all times. The invariant checker verifies the ones that can be computed.

1. Every ledger transaction balances per currency (debits = credits) and has at least 2 entries.
2. Entries and ledger transactions are never updated or deleted.
3. Across all accounts, total debits equal total credits in every currency.
4. Every customer account's cached `posted_balance` equals the balance derived from its entries.
5. Every customer account's `held_balance` equals the sum of its ACTIVE holds.
6. Available balance (posted − held) is never negative for accounts that disallow it. See the M8 policy for forced reversals.
7. An entry's currency equals its account's currency.
8. One idempotency key produces at most one effect, and every repeat of it gets that effect's result (M6: the claim, then the unique key on the movement row).
9. Payment status changes only through allowed transitions, and every change is recorded in history.
10. No ledger transaction is reversed more than once.
11. Every transfer and funding points to exactly one ledger transaction, and only to accounts of its own client in its own currency (M4b, enforced by the database).
12. An audit row exists for an action if and only if the action was committed (M4b, same transaction).

## 7. Failure modes

*This table grows with each milestone. Every row must point to a test.*

| Scenario | Outcome | Mechanism | Test (milestone) |
|---|---|---|---|
| Client retries after a timeout | Money moves once, and the retry gets the original response, marked `Idempotent-Replayed: true`, even if the money has since been spent. (Before M6: 409 with the original's id.) | The claim, first in the transaction (ADR-0023) | M6 (`TransferServiceIT`, `FundingServiceIT`, `TransfersApiIT`, `FundingsApiIT`) |
| The same request arrives twice at the same moment | One is applied; the other waits for it at the claim and gets the same response. (Before M6: 409, or 422 if the first spent the money.) | The claim's insert waits for the uncommitted one | M6 (`TransferServiceIT`: held-transaction races and an 8-way race; `IdempotencyKeysIT`; `ConcurrencyIT`) |
| The same key comes with a different body, or a transfer's key is reused for a funding | 422 `idempotency-key-reused`; nothing moves | The claim's operation and fingerprint are compared (ADR-0023) | M6 (`IdempotencyKeysIT`, `TransferServiceIT`, `FundingServiceIT`, `TransfersApiIT`, `FundingsApiIT`) |
| A retry arrives while the first attempt is still running, and the first takes more than 2 seconds | 409 `request-in-progress` with `Retry-After: 1`; nothing moves; a later retry gets the first's result. (Before M6: 503 `account-busy`.) | `lock_timeout` set before the claim | M6 (`IdempotencyKeysIT`, `TransferServiceIT`, `TransfersApiIT`) |
| The first attempt fails (404, 422, 503) | The key stays free: a retry runs again, and may now succeed | The claim rolls back with everything else | M6 (`TransferServiceIT`, `TransfersApiIT`) |
| A retry arrives after its claim expired and was deleted | 409 `duplicate-request` with the original's id, before any money moves, even if the money has since been spent | The movement row keeps its key; checked right after the claim | M6 (`TransferServiceIT`, `FundingServiceIT`, `IdempotencyCleanupIT`, `TransfersApiIT`) |
| A bug lets a duplicate past the claim | Still applied once; the loser gets 409 `duplicate-request` | `UNIQUE (client_id, idempotency_key)` on transfers and fundings | M6 (`UniqueKeyBackstopIT`, with the claim layer switched off); M4b (`MoneyMovementSchemaIT`) |
| Another client sends the same key, even with this client's exact body | Its own request: never a replay of this client's, and this client's accounts are a 404 to it | Claims are keyed by client | M6 (`IdempotencyKeysIT`, `TransferServiceIT`, `TransfersApiIT`) |
| A compromised app deletes every claim | Clients lose their replays; money still never moves twice | The app can delete claims but not movement rows; the unique keys stay | M6 (`AppRolePrivilegesIT`, `UniqueKeyBackstopIT`) |
| Two withdrawals race on one account | At most the available funds are spent | Accounts locked, then funds checked under the lock (ADR-0005); SQL deltas; `CHECK` backstop | M5 (`ConcurrencyIT`, `BalanceChangesTest`, `BalanceChangesPropertiesTest`); M4b smoke test (`concurrentTransfersNeverOverdrawTheSource`) |
| Opposite transfers A→B and B→A at once, or postings naming the same accounts in any order | No deadlock | Customer accounts locked in ascending id order, in one statement (ADR-0005) | M5 (`ConcurrencyIT`, run without retries; `PostingLocksIT` proves the order) |
| An account is closed while a transfer waits for its lock | The transfer is rejected (422 `account-closed`); nothing moves. (Before M5, the transfer credited the closed account.) | Status re-read under the lock | M5 (`PostingLocksIT`) |
| Something holds an account's lock for a long time (a slow transaction, a forgotten `psql` session) | Requests for that account give up after 2 seconds with 503 `account-busy`, and nothing moves. Other accounts are unaffected | `lock_timeout` per transaction (ADR-0022) | M5 (`PostingLocksIT`, `TransfersApiIT`) |
| Postgres aborts a transfer or funding to break a deadlock | It's retried, and the money moves once. 503 if all 3 attempts are aborted | `RetryingTransactions` (ADR-0022) | M5 (`TransferServiceIT`, `FundingServiceIT`: real forced deadlocks; `RetryingTransactionsTest`) |
| A thousand transfers and fundings, with overdraws and repeated keys, hit four accounts at once | Every balance equals what the successful requests add up to; every success has its row and audit row; the invariant checker is clean every time it looks, during the load and after | All of the above | M5 (`ConcurrencyIT`) |
| A bug breaks the funds check under the lock | The `CHECK` constraint still refuses the overdraft. Its error is deliberately not translated, so the bug surfaces as a 500 instead of passing for a routine 422 | `CHECK` backstop | M5 planted-bug check; `LedgerSchemaIT` |
| Server crashes mid-transfer | Transaction rolls back; nothing half-applied | Single DB transaction | M3a (`PostingServiceIT`: a failed posting leaves no entries) |
| A posting would overdraw a customer | Rejected with `InsufficientFundsException` (422) before anything is written | Funds checked under the lock (`BalanceChanges`, M5); `CHECK` backstop behind it | M3a (`PostingServiceIT`, `RandomPostingsIT`); M5 (`BalanceChangesTest`) |
| Code (or a person) writes ledger rows by hand, skipping Java's checks | Unbalanced, empty, non-positive, or wrong-currency writes are rejected; edits and deletes are rejected | Constraints and triggers in V2 | M3a (`LedgerSchemaIT`) |
| A bug corrupts a cached balance or writes one side of a transaction | The invariant checker reports it | `InvariantChecker` | M3a (`InvariantCheckerIT`) |
| A client tries another client's account id (IDOR) | 404, identical to an id that doesn't exist | Ownership in the SQL (`accountOwnedBy`) | M4a (`AccountsApiIT`) |
| Someone sends a guessed, tampered, revoked, or disabled client's key | 401, the same answer for every case | `ApiKeyVerifier` (constant-time compare, no reason given) | M4a (`ApiSecurityIT`, `ApiKeyVerifierIT`) |
| A new endpoint is added without a security rule | Unreachable, even with every scope | Deny-by-default rules | M4a (`ApiSecurityIT`) |
| An API key leaks | Revoke it; it stops working at once. Only its hash was ever stored. | `revoked_at`; hash-only storage | M4a (`ApiKeyVerifierIT`) |
| A client sends `10.5`, `"1050"`, or `1e3` as an amount | 400 naming `amount.amount`; nothing moves. (Before M4b's fix, `10.5` silently became 10.) | Strict parsing (ADR-0021) | M4b (`TransfersApiIT`) |
| A transfer names another client's account | 404, identical to a missing account. Even if the Java check were missing, the database rejects the row and the whole transfer rolls back | `accountOwnedBy`; composite foreign keys (ADR-0018) | M4b (`TransfersApiIT`, `MoneyMovementSchemaIT`; planted-bug check) |
| Something fails after the money moved but before the transfer or audit row is written | Everything rolls back together | One transaction; `AuditLog` requires one (`MANDATORY`) | M4b (`AuditLogIT`, `TransferServiceIT`) |
| A money endpoint is added without its scope rule | Still refused: the service checks the scope too | `Caller.requireScope` | M4b (`FundingServiceIT`, `ApiSecurityIT`) |
| A compromised app tries to read or erase the audit trail | Denied | INSERT-only grant; append-only triggers | M4b (`AppRolePrivilegesIT`, `AuditLogSchemaIT`) |
| A client reports an error | Its `requestId` finds the request's log line and audit row | Request ids (ADR-0021) | M4b (`RequestIdIT`, `AccountsApiIT`, `TransfersApiIT`) |
| The app is tricked into running arbitrary SQL (e.g., injection) | It can't edit history, change account identity, disable triggers, set replica mode, alter or drop the schema, or touch migration history | Restricted login with least-privilege grants | M3b (`AppRolePrivilegesIT`) |
| Crash after authorization, before bank submit | Sweeper submits exactly once | ADR-0010 | M9b |
| Bank succeeds, then the call times out | Instruction UNKNOWN, hold kept, settled once | ADR-0010 | M9b |
| Duplicate bank callback / settle–void race | One terminal state | Conditional transitions | M9b |
| Hold expires while the instruction is in flight | NEEDS_REVIEW, hold kept | ADR-0006 | M9b |
| Relay crashes after publish, before marking | Event re-published; consumers dedupe | ADR-0009 | M10 |

## 8. Security

The general rules are in `CLAUDE.md`.

### API authentication and authorization (M4a, [ADR-0016](adr/0016-api-key-format-transport-and-bootstrap.md), [ADR-0017](adr/0017-tenant-isolation-and-error-format.md))

1. **The client sends** `Authorization: Bearer dbl_<key id>_<secret>`.
2. **`ApiKeyAuthenticationFilter` checks the key:**
   - No header: the request carries on unauthenticated.
   - An invalid key, or a scheme other than Bearer: 401 straight away.
   - A valid key: the request runs as that client, with one authority per scope.
3. **`ApiKeyVerifier` decides validity:** look up the key id, compare `SHA-256(secret)` in constant time, reject revoked keys and disabled clients. All failures look the same.
4. **The security rules map each endpoint to a scope** (table below). Anything without a rule is denied, even with a valid key. Services that move money check the scope again.
5. **Controllers look accounts up with `accountOwnedBy(client, id)`,** so another client's account, a system account, or a missing one all give the same 404.

| Endpoint | Scope | Since |
|---|---|---|
| `POST /v1/accounts` | `write` | M4a |
| `GET /v1/accounts/{id}`, `GET /v1/accounts/{id}/entries` | `read` | M4a |
| `POST /v1/transfers` (needs `Idempotency-Key`) | `write` | M4b |
| `GET /v1/transfers/{id}` | `read` | M4b |
| `POST /v1/fundings` (needs `Idempotency-Key`) | `admin`, for the caller's own accounts only | M4b |
| `GET /actuator/health` | none | M1 |

**Other rules:**
- The first client and key come from the command line (`clients create`), not an endpoint.
- Invalid credentials are always 401, even on a public path such as `/actuator/health`. Only a request with no `Authorization` header is treated as anonymous.
- Actuator endpoints other than health are never reachable: 401 without a key, 403 with one.
- History pages hold 50 entries by default and 100 at most, and the cursor is the id of the last entry returned.

### Request ids, the audit log, and strict input (M4b)

- **Request ids** ([ADR-0021](adr/0021-request-ids-and-strict-request-parsing.md)): `RequestIdFilter` runs before authentication. It gives each request a server-generated UUID, which goes:
  - in the `X-Request-Id` header and in every error's `requestId`
  - on every log line (the MDC)
  - on the audit, transfer, and funding rows

  Each request also leaves one access-log line with its method, route template, and status, never the raw path. That line is where rejected requests are recorded.
- **Audit log** ([ADR-0020](adr/0020-audit-log-in-the-business-transaction.md)): opening an account, a transfer, a funding, creating a client, and issuing a key are each audited in the same transaction as the action. The app can write the log but not read or change it.
- **Strict input:** an amount must be a JSON integer, and an unknown field is a 400. Bean Validation rejects malformed input (such as a zero amount) before any domain object is built. A domain `IllegalArgumentException` that reaches the API is a bug, and it's a 500.

### Idempotent retries (M6, [ADR-0023](adr/0023-idempotency-with-claim-first-and-replay.md))

- **Every money-moving POST** (`/v1/transfers`, `/v1/fundings`) requires `Idempotency-Key`: 1 to 255 characters from `A-Z a-z 0-9 _ . : -`. Keys belong to the client, across both operations.
- **The same key and the same request** get the original response: the same status, `Location`, and body, plus `Idempotent-Replayed: true`. "The same request" means the same values after parsing, so spacing and field order don't matter.
- **A replay is available for at least 24 hours** (`LEDGER_IDEMPOTENCY_RETENTION`). After that, the same key gets 409 `duplicate-request`; it never moves money again.
- **A failed request doesn't use up its key:** a retry runs again.
- **409 has two problem types.** A client must tell `duplicate-request` (done) from `request-in-progress` (not finished) by `type`, not by status.

### Errors

Every error is RFC 9457 Problem Details (`application/problem+json`) with a `requestId`. Stack traces and internal messages never reach a response.

| Status | `type` | When | Extra fields |
|---|---|---|---|
| 400 | `/problems/invalid-request` | Any malformed input: a missing or invalid header, a body that isn't JSON, an unknown field, a non-integer or non-positive amount, a wrong type in the path or query | `errors`: `[{field, message}]`, empty only when no single field is at fault |
| 401 | `/problems/unauthorized` | No key, or an invalid one | |
| 403 | `/problems/forbidden` | The key lacks the scope | |
| 404 | `/problems/account-not-found` | An account the client doesn't own (missing, another client's, or a system account: all alike). Transfers name the field | |
| 404 | `/problems/transfer-not-found` | A transfer the client doesn't own | |
| 409 | `/problems/duplicate-request` | The `Idempotency-Key` was used by a request that was carried out, and its claim has expired, so it can't be replayed (ADR-0023). Done: don't retry | `originalId` |
| 409 | `/problems/request-in-progress` | Another request with this `Idempotency-Key` still held it after the 2-second lock timeout. Not done yet: retry with the same key and body to get its result | `Retry-After: 1` header |
| 422 | `/problems/idempotency-key-reused` | The `Idempotency-Key` was already used for a different request: another body, or another operation | |
| 422 | `/problems/insufficient-funds` | The source can't cover the amount | |
| 422 | `/problems/currency-mismatch` | The amount isn't in the account's currency | |
| 422 | `/problems/same-account` | The source and destination are the same | |
| 422 | `/problems/account-closed` | A closed account would send or receive | |
| 422 | `/problems/amount-too-large` | Above the currency's limit (§10) | `maximum` |
| 500 | `/problems/internal-error` | Anything unexpected; logged on the server | |
| 503 | `/problems/account-busy` | Other requests held the account for longer than the 2-second lock timeout, or kept deadlocking with this one (ADR-0022). Nothing moved; retrying with the same `Idempotency-Key` is safe | `Retry-After: 1` header |

### Database trust model (M3b, [ADR-0015](adr/0015-least-privilege-database-roles.md))

| Layer | Protects against | Doesn't protect against |
|---|---|---|
| Constraints and triggers (V2) | *Mistakes* by any role: bugs, hand-written fixes, wrong migrations | A malicious owner (who can disable triggers or drop constraints) or superuser (who can `SET session_replication_role = replica`) |
| Privileges (V3) | The *application*, and anything that compromises its connection, such as SQL injection. It can't edit history, change account identity, disable triggers, alter the schema, or touch migration history. | The owner |
| The owner | Nothing: it's trusted, and used for migrations only | |

**Known gap:** Flyway runs at application startup, so the app *process* holds the owner's credentials. SQL injection through the app's connection is contained. Code execution inside the app process isn't. Running migrations as a separate deployment step closes this (M16).

## 9. Testing

Strategy: unit, property-based, integration against real Postgres, concurrency, API, architecture, and load tests. The invariant checker runs after every integration, concurrency, and load test.

**In place since M1:**
- **Unit tests** (`*Test`, Surefire, `./mvnw test`): no Docker.
- **Integration tests** (`*IT`, Failsafe, `./mvnw verify`): boot the real application against `postgres:18` in Docker through Testcontainers. `@ServiceConnection` points the datasource at the container. The Spring test context is cached, so test classes with the same configuration share one container.
- **First coverage** (`ApplicationIT`, M1): the health endpoint is UP; Actuator endpoints other than `health` are unreachable (401 without a key since M4a); Flyway created and seeded `currencies`.
- **Race tests without sleeps** (M4b, `TransferServiceIT`): to force a specific interleaving, a test holds one transaction open and waits until Postgres reports (`pg_locks`) that the other is blocked, then lets the first commit. Threads start together on a latch.
- **Concurrency tests** (M5; primer 05):
  - **Test helpers:** `HeldTransaction` holds an owner transaction open on its own thread and can be handed a next step. `DatabaseLocks` waits for a session blocked on a lock, takes an account's lock, and probes a lock with `NOWAIT`.
  - **Locking** (`PostingLocksIT`): lock order, a status change while a posting waits, the lock timeout, and system accounts never locked.
  - **Real deadlocks, forced** (`TransferServiceIT`, `FundingServiceIT`): the test's transaction and the service wait for each other. The service has waited longer, so Postgres aborts it, and the retry succeeds.
  - **The suite** (`ConcurrencyIT`): 1,000 mixed requests on four accounts with the invariant checker running throughout; and postings naming the same accounts in every order, straight through the posting service, so a deadlock couldn't be hidden by a retry. Since M6, its repeated requests must replay exactly the movement their original made, and claims, movement rows, and audit rows must match one to one.
  - **Each layer on its own:** the Java funds check (`BalanceChangesTest`), the `CHECK` backstop (`LedgerSchemaIT`), lock order without retries (`ConcurrencyIT`), and retries without a database (`RetryingTransactionsTest`).
- **Idempotency tests** (M6; primer 06):
  - **The claim on its own** (`IdempotencyKeysIT`): new, repeat, reused, per client, rolled back with its transaction, only inside a transaction, and the three ways a held claim can end (commit, rollback, past the lock timeout). The table's guards: `IdempotencyKeysSchemaIT`.
  - **The fingerprint** (`RequestFingerprintTest`, `MoneyMovementFingerprintsTest`): the encoding and two commands' hashes are pinned to values computed with `sha256sum`. A property test (`MoneyMovementFingerprintsPropertiesTest`) checks that two commands share a fingerprint exactly when they're the same request.
  - **The services** (`TransferServiceIT`, `FundingServiceIT`): replays, reuse, races, a deleted claim, another client's key.
  - **The unique-key backstop on its own** (`UniqueKeyBackstopIT`): the claim layer is replaced by a mock that lets every request through, and the movement rows' unique keys still apply each key once.
  - **Over HTTP** (`TransfersApiIT`, `FundingsApiIT`): the replayed response and its header, a re-serialized body, each problem type, and another client sending the same key.
  - **Cleanup** (`IdempotencyCleanupIT`): only expired claims go, in batches, on the configured schedule; and a retry after its claim is deleted still can't move money twice. `ClientsCommandIT` checks the command-line mode never schedules it.
- **Planted-bug checks:** at the end of each checkpoint, bugs are planted in a scratchpad copy of the code to confirm the tests catch them. The roadmap records each run.
- **CI** (`.github/workflows/ci.yml`): `./mvnw verify` on Temurin 25, plus a gitleaks scan of the full git history.

## 10. Money rules (M2)

Implemented in `io.github.jhanmodi.ledger.money` ([ADR-0002](adr/0002-money-as-integer-minor-units.md), [ADR-0013](adr/0013-currencies-as-a-java-enum.md)). Every example below is a test in `MoneyTest` or `MoneyAllocationTest`. The general rules are checked by property tests (`MoneyPropertiesTest`, `MoneyAllocationPropertiesTest`).

**Representation:** `Money(long minorUnits, CurrencyCode currency)`, immutable. Exponents: USD 2, EUR 2, JPY 0, KWD 3. Negative amounts are allowed (balance changes, system accounts). The rule that entry amounts are positive belongs to entries (M3).

### Arithmetic

| Operation | Result |
|---|---|
| 10.50 USD + 0.75 USD | 11.25 USD |
| 10.50 USD − 0.75 USD | 9.75 USD |
| USD + EUR (also −, compare) | `CurrencyMismatchException` |
| `Long.MAX_VALUE` + 1 minor unit | `ArithmeticException`. It never wraps around. |

Display, for logs and test output only: `1050 USD` → `10.50 USD`, `1000 JPY` → `1000 JPY`, `1234 KWD` → `1.234 KWD`, `−5 USD` → `-0.05 USD`.

### Rounding

Only `multiply(factor, roundingMode)` rounds. It always rounds to a whole minor unit, using the mode the caller names. There is no default.

| Calculation | HALF_EVEN | HALF_UP |
|---|---|---|
| 25¢ × 0.1 = 2.5¢ | 2¢ | 3¢ |
| 35¢ × 0.1 = 3.5¢ | 4¢ | 4¢ |
| −25¢ × 0.1 = −2.5¢ | −2¢ | −3¢ (Java's HALF_UP rounds ties away from zero) |
| ¥1001 × 0.5 = ¥500.5 | ¥500 | |

`UNNECESSARY` throws if the result isn't already a whole minor unit. Results that don't fit in a `long` throw.

### Allocation (largest remainder)

`allocate(ratios...)` splits an amount into one part per ratio:
1. Each part starts as its exact share (amount × ratio ÷ total of ratios), rounded down.
2. The leftover minor units go one at a time to the parts that lost the most to that rounding down. Ties go to the earlier part.
3. The parts always add up to exactly the original amount.

| Amount and ratios | Parts | Why |
|---|---|---|
| $100.00 in 1:1:1 | 33.34, 33.33, 33.33 | Three-way tie, so the earliest part gets the cent |
| $0.05 in 1:1 | 0.03, 0.02 | |
| $0.01 in 1:99 | 0.00, 0.01 | The 99 part lost 0.99¢ to rounding down, the 1 part only 0.01¢ |
| $1.00 in 1:2:4 | 0.14, 0.29, 0.57 | The second part lost the most (0.57¢). Leftover-to-first would give 0.15, 0.28, 0.57. |
| $0.05 in 1:1:1 | 0.02, 0.02, 0.01 | Two leftover cents, handed out one at a time |
| ¥100 in 1:0:1 | 50, 0, 50 | A zero ratio gets nothing |
| 1.000 KWD in 1:1:1 | 0.334, 0.333, 0.333 | Works in fils (3 decimals) |
| `Long.MAX_VALUE` in 2:1 | 6148914691236517205, 3074457345618258602 | Works across the whole range |

**Errors:**
- No ratios, a negative ratio, all ratios zero, or a negative amount: `IllegalArgumentException`.
- Ratios that add up to more than `Long.MAX_VALUE`: `ArithmeticException`.

**Properties** (checked across generated inputs):
- Parts add up to the original.
- There's one part per ratio, all in the same currency.
- Each part is within one minor unit of its exact share.
- A zero ratio gets zero.
- A larger ratio never gets a smaller part.
- The same input always gives the same parts.

### Amount limits (M4b)

Implemented in `transfers.AmountLimits` ([ADR-0018](adr/0018-transfers-and-funding-over-the-ledger.md), D4). Every example below is a test in `AmountLimitsTest`.

**Rule:** a single transfer or funding moves at most a fixed maximum in its currency. The maximum itself is allowed; one minor unit more is not. Each maximum is roughly a million US dollars of real value, so the cap means the same thing in every currency.

| Currency | Maximum | In minor units | Allowed | Rejected |
|---|---|---|---|---|
| USD | 1,000,000.00 | 100,000,000 | 1,000,000.00 | 1,000,000.01 |
| EUR | 1,000,000.00 | 100,000,000 | 1,000,000.00 | 1,000,000.01 |
| JPY | 150,000,000 | 150,000,000 | ¥150,000,000 | ¥150,000,001 |
| KWD | 300,000.000 | 300,000,000 | 300,000.000 | 300,000.001 |

**Why a fixed number of minor units would be wrong:** 100,000,000 minor units is $1,000,000.00, but only ¥100,000,000 (about $670,000) and 100,000.000 KWD (about $325,000). A single cap in minor units, or in major units, would mean very different real amounts per currency.

**What it is not:** a per-client or per-day risk limit. It's a sanity cap against wrong input, such as a misplaced decimal point or a confused currency. A violation is a 422 that states the maximum. Amounts must also be positive; that's a 400, because a zero or negative amount is malformed input rather than a business decision.

**Adding a currency:** `AmountLimits.maximum` switches over every currency with no default branch, so the build fails until the new currency has a limit.

## 11. Operations

*Filled in from M11 and M14:* reconciliation, metrics, dashboards, and runbooks (e.g., resolving NEEDS_REVIEW).
