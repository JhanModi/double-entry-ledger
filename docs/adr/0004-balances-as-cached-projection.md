# ADR-0004: Balances are a cached projection of entries

- **Status:** Accepted
- **Date:** 2026-10-01

## Context
Entries are the source of truth. A funds check must be fast, and concurrency control (ADR-0005) needs a row to lock. Summing every entry on each transfer gets slower as history grows.

## Options considered
- **No stored balance** (`SUM` every time). Always correct, but O(history) per request, and there's no natural lock target.
- **Cached balance on the account row**, updated in the same transaction as the entries.
- **Periodic snapshots plus the entries since the last snapshot.** Scales for huge histories, but more complex.

## Decision
- **CUSTOMER accounts** carry `posted_balance` and `held_balance`. Both are updated in the *same transaction* as the entries, using a SQL delta (`SET posted_balance = posted_balance + :delta`). Never read in Java, modify, then write back.
- **SYSTEM accounts** (bank settlement, FX pools, fees) have *no* cached balance and are *never locked*. Their balances are computed from entries. Snapshots can be added later if needed.
- **Invariant checker:** runs in one read-only REPEATABLE READ transaction, so it sees a single consistent snapshot. It verifies:
  - cached balance = balance derived from entries, for every customer account
  - every currency nets to zero across all accounts
  - `held_balance` = sum of ACTIVE holds (ADR-0006)

## Consequences
- O(1) funds checks and a natural lock target.
- System accounts never become a hot row that every request waits on.
- The cache could drift if some code bypassed the posting service. Mitigations:
  - one write path, enforced by ArchUnit
  - the invariant checker, run after every integration, concurrency, and load test
  - the database role can't update entries

## How to explain it
"Entries are the truth. The balance on the account row is a cache updated in the same transaction, which gives me fast funds checks and something to lock. An invariant checker proves the cache matches the entries. Shared system accounts aren't cached, so they never serialize traffic."
