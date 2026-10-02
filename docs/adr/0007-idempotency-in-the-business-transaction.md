# ADR-0007: Idempotency recorded inside the business transaction

- **Status:** Accepted
- **Date:** 2026-10-01

## Context
A client sends a transfer, the network times out, and the client retries. The money must move exactly once, and the retry must get the same answer as the original.

## Options considered
- **Servlet filter that caches responses.** It runs outside the business transaction. A crash after the business commit but before the cache write lets a retry run the operation a second time.
- **Redis cache.** A second source of truth with the same dual-write gap.
- **Idempotency row in the same Postgres transaction as the operation.** All-or-nothing.

## Decision
**Table:** `idempotency_keys(client_id, key, operation, request_hash, response_status, response_body, created_at, expires_at)`, with `PRIMARY KEY (client_id, key)`.

**Flow** (all inside the operation's transaction):
1. The first statement claims the key: `INSERT … ON CONFLICT DO NOTHING`.
2. If the insert succeeded: do the work, store the response on the row, commit.
3. If the key already existed: read the row.
   - A different `operation` or `request_hash` returns **422**.
   - Otherwise, **replay** the stored response.
4. A concurrent duplicate waits on the unique index until the first transaction finishes. If the first commits, the duplicate replays its response. If the first rolls back, the duplicate proceeds normally.

**Rules:**
- **Hashing:** `request_hash` is computed over the validated command, in a fixed field order, plus the operation (method + route template). Never over the raw bytes, because whitespace or field order would change the hash.
- **What to store:** only outcomes that were actually executed, meaning 2xx and domain 4xx such as "insufficient funds". Never store 400, 401, 403, 429, or 5xx. Those roll back, which releases the key, so retrying them is safe.
- **Permanent duplicate guard:** `UNIQUE (client_id, idempotency_key)` on the business rows themselves (`transfers`, `payments`). A retry after the stored response has expired gets **409**, never a second debit.
- **Expiry:** `expires_at` uses database time. A cleanup job deletes expired rows. They aren't financial records, so deleting them is allowed.
- **Waiting duplicates:** bounded by `lock_timeout`, returning **409** with `Retry-After`.

## Consequences
- Exactly-once *effect* for everything inside the database, with no extra infrastructure.
- This holds only because nothing external happens inside the request transaction. Bank calls use the bank's own idempotency (ADR-0010).
- If the isolation level ever moves to REPEATABLE READ, `ON CONFLICT` raises serialization errors instead of waiting. Revisit this ADR if that happens.

## How to explain it
"The idempotency key is claimed in the same transaction that moves the money, so either both are committed or neither is. A retry after a lost response replays the stored result. A retry with a different body is rejected. A unique constraint on the transfer row is a permanent backstop even after the stored response expires."
