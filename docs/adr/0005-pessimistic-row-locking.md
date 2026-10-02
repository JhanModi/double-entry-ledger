# ADR-0005: Pessimistic row locking in a fixed order

- **Status:** Accepted
- **Date:** 2026-10-01

## Context
Two concurrent withdrawals of 80 from a balance of 100 can both read 100, both pass the funds check, and leave the balance at −60. The design must make this impossible, including when 1,000 requests hit one account at once.

## Options considered
- **Optimistic concurrency** (version column, `UPDATE … WHERE version = ?`, retry on conflict). Efficient under low contention. Under heavy contention on one account, most attempts fail and retry, causing a retry storm.
- **Pessimistic locking** (`SELECT … FOR NO KEY UPDATE`). Others wait their turn. Access to one account becomes serialized, but behavior is predictable.
- **SERIALIZABLE isolation.** The database detects conflicts and aborts transactions. Every operation needs retry handling, it's harder to reason about, and it causes similar storms under contention.

## Decision
- **Lock** every *customer* account an operation touches with `SELECT … FOR NO KEY UPDATE`, in **ascending id order**.
  - **Why ascending order:** transfers A→B and B→A could otherwise each hold one lock and wait forever for the other (a deadlock).
  - **Why `NO KEY UPDATE`:** a plain `FOR UPDATE` lock also blocks the lightweight `KEY SHARE` locks that foreign-key checks take when entries are inserted. `FOR NO KEY UPDATE` doesn't.
- **Re-read balances after locking**, and do the funds check on the locked values.
- **`SET LOCAL lock_timeout`**, so a waiting request fails fast (SQLSTATE `55P03`) instead of hanging. It returns a retryable error to the client.
- **Retry the whole transaction**, a bounded number of times, on deadlock (`40P01`) or serialization failure (`40001`).
- **Backstop constraint:** `CHECK (allow_negative OR posted_balance - held_balance >= 0)`. Even buggy code can't overdraw an account.
- **Isolation level:** READ COMMITTED.
- **No network calls** inside these transactions.

## Consequences
- No overdraft under any concurrency.
- Simple mental model: one writer per account at a time.
- A hot account's throughput is limited by how long the lock is held, so transactions stay short.
- Waiting requests hold database connections, so `lock_timeout` and connection-pool sizing matter.

## How to explain it
"I lock the accounts' rows before checking funds, always in id order so two opposite transfers can't deadlock. I picked pessimistic over optimistic locking because under heavy contention on one account, optimistic retries snowball. A check constraint in the database is the final guard."
