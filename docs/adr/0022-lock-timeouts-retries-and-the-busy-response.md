# ADR-0022: Lock timeouts, deadlock retries, and the busy response

- **Status:** Accepted
- **Date:** 2026-10-02

## Context
ADR-0005 decided that postings lock customer accounts in ascending id order, check funds under the lock, set a `lock_timeout`, and retry deadlocks. It didn't fix the details, and M4b's code had none of them in place:
- **A lock wait had no limit.** If a transaction held an account's row lock (a slow transaction, or a forgotten `psql` session), every request for that account waited for it, each holding one of the pool's 10 connections. Enough stuck requests would stall the whole API, reads included.
- **A deadlock would reach the client as a 500.** Two transactions that each wait for a lock the other holds are a deadlock. Postgres detects one after `deadlock_timeout` (1 second by default) and aborts one of the transactions with SQLSTATE `40P01`. Transfers can't deadlock today, because every posting locks in the same order. Reversals (M8), holds (M9), and FX (M12) will add new ways of taking locks.
- **The client had no way to know when a retry is safe.**

## Options considered

**Where to set `lock_timeout`:**
- **Per transaction, at the start of each posting** (`set_config('lock_timeout', …, true)`, the parameterized form of `SET LOCAL`). It sits next to the locking code and applies to every posting, whoever calls it. It ends with the transaction.
- **On every pooled connection** (Hikari's `connection-init-sql`). Set once, but it applies to every statement, and nothing near the locking code shows it.
- **On the database login** (`ALTER ROLE … SET`). The database enforces it, but the login's name comes from the environment, and a setting on the `ledger_app` group role doesn't pass to its members.

**How to retry:**
- **A small hand-written helper** that runs the transaction and retries it. Every branch is visible and unit-tested.
- **Spring Framework 7's built-in `RetryTemplate`** (`org.springframework.core.retry`, already on the classpath). Well tested, but more API than this job needs, and its annotation form (`@Retryable`) must be ordered carefully around `@Transactional`.
- **The Spring Retry library.** A new dependency for something either option above already does.

**What the client gets when the wait times out:**
- **503 Service Unavailable with `Retry-After`.** It means "temporarily unable, try again later", and many HTTP clients retry it automatically.
- **409 Conflict.** Here it already means "duplicate: this was already done" (ADR-0019). A client that treats 409 as done would silently drop a transfer that never happened.
- **429 Too Many Requests.** It means the client is sending too much, and it's reserved for rate limiting (M15b).

## Decision
- **`lock_timeout` is set per transaction at the start of each posting,** with `set_config('lock_timeout', :value, true)`. The value is `ledger.lock-timeout` (environment variable `LEDGER_LOCK_TIMEOUT`), **2 seconds** by default, and it must be positive: 0 would mean waiting forever.
  - **Why 2 seconds:** it's longer than Postgres's 1-second `deadlock_timeout`, so a real deadlock is still detected as a deadlock and retried rather than reported as a timeout. A posting holds its locks for milliseconds, so a 2-second wait means something is genuinely stuck.
  - It lasts until the transaction ends, so it also bounds lock waits after the posting, such as a transfer's insert waiting on another request with the same idempotency key.
  - A lock wait that times out (SQLSTATE `55P03`) becomes `AccountBusyException`. Nothing was written.
- **Deadlocks and serialization failures are retried; lock timeouts aren't.**
  - A hand-written helper in the ledger module runs the whole transaction and retries it on `40P01` (deadlock) or `40001` (serialization failure). Errors are recognized by SQLSTATE, not by Spring's exception classes or by message text.
  - It's safe because the failed attempt rolled back completely, and the idempotency key still guards the retry. `40001` can't happen at READ COMMITTED; it's listed because it would appear if an operation ever ran at a stricter isolation level.
  - **At most 3 attempts,** with a short random delay ("jitter") before each retry, so two aborted transactions don't collide again in lockstep.
  - **Only when the helper started the transaction.** If it joined a caller's transaction, that transaction is already aborted, so the error is rethrown.
  - **A lock timeout is never retried:** it means the account is busy, and retrying at once adds load exactly where it hurts. The client retries later.
- **The client gets 503 `/problems/account-busy` with `Retry-After: 1`** when a lock wait times out or the retries run out. Nothing moved, and retrying with the same `Idempotency-Key` is safe.

## Consequences
- **No request waits on a lock for more than 2 seconds,** so a stuck transaction can't take the whole connection pool with it.
- **A deadlock costs the client nothing but time,** as long as it clears within 3 attempts.
- **Retries can hide a bug:** a broken lock order would look slow but correct. So the deadlock tests run against the posting service *without* the retry helper, and every retry is logged with its request id.
- **Waiting on another request's idempotency key also counts as a lock wait.** Until M6, the second of two same-key requests can get 503 instead of 409 if the first takes over 2 seconds to commit. The money still moves once. M6 turns this into ADR-0007's 409 "in progress".
- **Waiting requests still hold pool connections.** The lock timeout bounds the wait; the pool size and Hikari's own 30-second wait for a connection are left for load testing (M15).

## How to explain it
"Every posting sets a 2-second lock timeout for its transaction, so no request can hang behind a stuck lock. If Postgres detects a deadlock, I retry the whole transaction up to three times, which is safe because the failed attempt rolled back completely. A lock timeout I don't retry: the client gets a 503 with Retry-After, and because nothing moved, it can retry with the same idempotency key."
