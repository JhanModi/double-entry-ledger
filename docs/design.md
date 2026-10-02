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
| `ledger` | accounts, ledger transactions, entries, balances, invariant checker | M3, M5 |
| `clients` | API clients, API keys, scopes | M4 |
| `transfers` | instant internal transfers | M4 |
| `idempotency` | idempotency keys | M6 |
| `audit` | append-only audit log | M4 |
| `payments` | payments, holds, bank instructions, recovery sweeper, `BankRail` | M9a, M9b |
| `outbox` | outbox events, relay, `EventPublisher` | M10 |
| `reconciliation` | statement import, matching, results | M11 |
| `fx` | rates, quotes, conversions | M12 |

## 4. Data model

*Filled in from M3.* Schema overview and constraints: see the decisions in [ADR-0003](adr/0003-entry-direction-and-positive-amount.md), [ADR-0004](adr/0004-balances-as-cached-projection.md), and [ADR-0006](adr/0006-holds-table.md).

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
| Server crashes mid-transfer | Transaction rolls back; nothing half-applied | Single DB transaction | M3/M5 |
| Crash after authorization, before bank submit | Sweeper submits exactly once | ADR-0010 | M9b |
| Bank succeeds, then the call times out | Instruction UNKNOWN, hold kept, settled once | ADR-0010 | M9b |
| Duplicate bank callback / settle–void race | One terminal state | Conditional transitions | M9b |
| Hold expires while the instruction is in flight | NEEDS_REVIEW, hold kept | ADR-0006 | M9b |
| Relay crashes after publish, before marking | Event re-published; consumers dedupe | ADR-0009 | M10 |

## 8. Security

*Filled in from M4.* See [ADR-0011](adr/0011-api-key-authentication.md) and the security rules in `CLAUDE.md`.

## 9. Testing

*Filled in from M1.* Strategy: unit, property-based, integration against real Postgres, concurrency, API, architecture, and load tests. The invariant checker runs after every integration, concurrency, and load test.

## 10. Operations

*Filled in from M11 and M14:* reconciliation, metrics, dashboards, and runbooks (e.g., resolving NEEDS_REVIEW).
