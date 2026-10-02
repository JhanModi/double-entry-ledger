# Design: Double-Entry Ledger & Payments API

> **Status:** design only. Nothing below is implemented yet. Each section is filled in as its milestone lands (see [roadmap](roadmap.md)). Public-facing docs describe only what exists.

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
| `ledger` | accounts, ledger transactions, entries, balances, invariant checker | M3a, M3b, M5 |
| `clients` | API clients, API keys, scopes | M4 |
| `transfers` | instant internal transfers | M4 |
| `idempotency` | idempotency keys | M6 |
| `audit` | append-only audit log | M4 |
| `payments` | payments, holds, bank instructions, recovery sweeper, `BankRail` | M9a, M9b |
| `outbox` | outbox events, relay, `EventPublisher` | M10 |
| `reconciliation` | statement import, matching, results | M11 |
| `fx` | rates, quotes, conversions | M12 |

## 4. Data model

Since M3a. Migration: `V2__create_ledger.sql`. Decisions: [ADR-0003](adr/0003-entry-direction-and-positive-amount.md), [ADR-0004](adr/0004-balances-as-cached-projection.md), [ADR-0014](adr/0014-uuidv7-ids.md).

| Table | Holds | Key rules (enforced by the database) |
|---|---|---|
| `currencies` | Supported currencies and exponents | Mirrors the `CurrencyCode` enum (ADR-0013) |
| `accounts` | What each account is, plus cached balances for customers | `normal_side` matches `type`; customers are liabilities; only customers have cached balances; available ≥ 0; identity columns never change; never deleted |
| `ledger_transactions` | One row per posting: type, description, business date | At least 2 entries, and balanced per currency, checked at commit; append-only |
| `entries` | One row per line: account, direction, positive amount | `amount > 0`; currency must match the account's (composite FK); append-only |

All ids are UUIDv7. Customer `posted_balance` is a cache of the entries, updated in the same transaction by SQL delta. System accounts have NULL balance columns, and their balance is derived from entries.

## 5. Key flows

### 5.1 Transfer (M4–M6)
All steps run in one database transaction at READ COMMITTED:
1. Authenticate, check scope, and validate.
2. Claim the idempotency key ([ADR-0007](adr/0007-idempotency-in-the-business-transaction.md)).
3. Lock customer accounts in id order ([ADR-0005](adr/0005-pessimistic-row-locking.md)).
4. Check ownership and available funds.
5. Insert entries and apply balance deltas, then write the outbox event and audit row.
6. Store the response and commit.

### 5.2 Payment lifecycle (M9a–M9b)

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
8. One idempotency key produces at most one effect.
9. Payment status changes only through allowed transitions, and every change is recorded in history.
10. No ledger transaction is reversed more than once.

## 7. Failure modes

*This table grows with each milestone. Every row must point to a test.*

| Scenario | Outcome | Mechanism | Test (milestone) |
|---|---|---|---|
| Client retries after a timeout | Money moves once; the stored response is replayed | ADR-0007 | M6 |
| Two withdrawals race on one account | At most the available funds are spent | ADR-0005 | M5 |
| Opposite transfers A→B and B→A at once | No deadlock | ADR-0005 (lock order) | M5 |
| Server crashes mid-transfer | Transaction rolls back; nothing half-applied | Single DB transaction | M3a (`PostingServiceIT`: a failed posting leaves no entries) |
| A posting would overdraw a customer | Rejected; the whole posting rolls back | `CHECK` backstop, translated to `InsufficientFundsException` | M3a (`PostingServiceIT`, `RandomPostingsIT`) |
| Code (or a person) writes ledger rows by hand, skipping Java's checks | Unbalanced, empty, non-positive, or wrong-currency writes are rejected; edits and deletes are rejected | Constraints and triggers in V2 | M3a (`LedgerSchemaIT`) |
| A bug corrupts a cached balance or writes one side of a transaction | The invariant checker reports it | `InvariantChecker` | M3a (`InvariantCheckerIT`) |
| The app is tricked into running arbitrary SQL (e.g., injection) | It can't edit history, change account identity, disable triggers, set replica mode, alter or drop the schema, or touch migration history | Restricted login with least-privilege grants | M3b (`AppRolePrivilegesIT`) |
| Crash after authorization, before bank submit | Sweeper submits exactly once | ADR-0010 | M9b |
| Bank succeeds, then the call times out | Instruction UNKNOWN, hold kept, settled once | ADR-0010 | M9b |
| Duplicate bank callback / settle–void race | One terminal state | Conditional transitions | M9b |
| Hold expires while the instruction is in flight | NEEDS_REVIEW, hold kept | ADR-0006 | M9b |
| Relay crashes after publish, before marking | Event re-published; consumers dedupe | ADR-0009 | M10 |

## 8. Security

API authentication arrives in M4 ([ADR-0011](adr/0011-api-key-authentication.md)). The general rules are in `CLAUDE.md`.

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
- **Current coverage** (`ApplicationIT`): the health endpoint is UP; Actuator endpoints other than `health` return 404; Flyway created and seeded `currencies`.
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

## 11. Operations

*Filled in from M11 and M14:* reconciliation, metrics, dashboards, and runbooks (e.g., resolving NEEDS_REVIEW).
