# ADR-0023: Idempotency with a claim first and a replay

- **Status:** Accepted
- **Date:** 2026-10-03
- **Supersedes:** ADR-0019 entirely. ADR-0007 in part: what a key remembers, how a replay is built, and how the operation is named. The rest of ADR-0007 stands.

## Context
ADR-0019 shipped interim idempotency in M4b: an `Idempotency-Key` header, and `UNIQUE (client_id, idempotency_key)` on the transfer and funding rows. Money could never move twice, but a retry wasn't fully safe:
1. A retry got 409 with the original's id, not the original response. Fundings have no `GET`, so a client couldn't even look the result up.
2. The same key with a different body also got 409. A client bug looked exactly like an honest retry.
3. Two identical requests at once could give the second 422 "insufficient funds", because the balance was checked before the key's uniqueness.
4. A same-key request waiting more than 2 seconds got 503 `account-busy` (ADR-0022), though no account was busy.

ADR-0007 already decided the shape of the fix: claim the key first, in the same transaction as the operation, hash the request, and replay. Five details were left to M6.

## Options considered

**D1, what a key remembers:**
- **Successes only.** A failed request rolls back everything, its claim included, so the key stays free and a retry runs again.
- **Successes and business failures** (ADR-0007 as written). A retry of a failed request gets the same 422 until the key expires. That needs a savepoint after the claim, so the claim survives the work's rollback, plus a stored error response.

**D2, what a replay sends back:**
- **Rebuild the response from the business row,** found by its key. The row is append-only, so the rebuilt response is the original's.
- **Store the response's status and body on the claim row.** Byte-identical even across deploys, but the response JSON is built in `web` after the service commits, so either `transfers` would build HTTP JSON (against ADR-0001's module direction) or `web` would own the transaction. The app would also need UPDATE on claims.

**D3, the request fingerprint:**
- **A SHA-256 hash of the validated command,** in a fixed, unambiguous encoding.
- **A hash of the raw body bytes.** Different spacing or field order would make an honest retry a 422.
- **Store the fields themselves and compare them.** Keeps a second copy of client data (descriptions, references) for a day, and needs columns per operation.

**D4, a duplicate that arrives while the first is still running:**
- **Wait, bounded by the lock timeout, then 409 `request-in-progress` with `Retry-After`.**
- **503 `account-busy`, as before.** Simple clients retry any 5xx, but no account is busy, and a 5xx counts as a server failure in error-rate monitoring.
- **Don't wait (`NOWAIT`).** Most races would end in a needless 409 that waiting a few milliseconds would have turned into a replay.

**D5, expiry and cleanup:**
- **A scheduled job in the app** deletes expired claims. The app needs DELETE on the claims table.
- **A separate maintenance process with its own role.** Better privilege separation, but another process to run.
- **Never delete.** The table grows without limit and keeps fingerprints forever.

## Decision

**The table:** `idempotency_keys (client_id, idempotency_key, operation, request_hash, created_at, expires_at)`, with `PRIMARY KEY (client_id, idempotency_key)`. It holds no response: D1 and D2 make one unnecessary.
- Keys are per client, across operations: a transfer and a funding can no longer share a key (ADR-0019 planned this).
- `operation` is `TRANSFER` or `FUNDING`, rather than ADR-0007's method and route, because the command the fingerprint is computed from doesn't know its route.

**The flow,** inside the operation's own transaction (started by `RetryingTransactions`, ADR-0022):
1. Set the transaction's `lock_timeout` (the same `ledger.lock-timeout`, 2 seconds by default).
2. **Claim the key:** `INSERT … ON CONFLICT (client_id, idempotency_key) DO NOTHING`. If another transaction has inserted the same key and not yet committed, this waits to see how it ends.
3. **A new claim:**
   - If the business table already has a row with this key, its claim expired and was deleted. Answer 409 `duplicate-request` with the original's id, and move nothing.
   - Otherwise do the work as before: post, insert the business row, audit, commit. The claim commits with them, or rolls back with them.
4. **An existing claim:**
   - A different `operation` or `request_hash`: 422 `idempotency-key-reused`.
   - Otherwise a **replay**: the business row is read back and returned, and nothing new happens.

**D1-A, only successes are remembered.** A 404 or 422 rolls back the claim with everything else. A failed request has no effect to protect, because nothing external happens inside the transaction (ADR-0007), and it keeps the M4b behaviour for failures. Savepoints, where a failure can be silently lost, are avoided.

**D2-A, a replay is rebuilt from the business row.** The same status, body, and `Location` as the original, plus `Idempotent-Replayed: true`. Claims never change, so the app gets no UPDATE on them.

**D3-A, the fingerprint** is SHA-256 over this encoding: the version tag `v1`, the operation, then each field's name and value in a fixed order. Each item is written as its UTF-8 length in bytes, a colon, and its bytes, so `"ab" + "c"` and `"a" + "bc"` can't produce the same text. A missing value is written as `-`, which no length prefix can start with. The idempotency key itself isn't part of it. A test pins the encoding of one transfer and one funding to values computed by an independent tool.

**D4-A, a waiting duplicate** gets 409 `/problems/request-in-progress` with `Retry-After: 1` if the other request still holds the key after the lock timeout. Its detail says the first request isn't finished and that retrying with the same key will return its result.

**D5-A, expiry:**
- `expires_at` is `created_at` plus the retention, 24 hours by default (`ledger.idempotency.retention`, environment variable `LEDGER_IDEMPOTENCY_RETENTION`), in database time.
- A claim is honoured until it's actually deleted, so the promise to clients is "at least 24 hours".
- A scheduled job, every 10 minutes by default (`ledger.idempotency.cleanup-interval`), deletes expired claims 1,000 at a time, each batch in its own short transaction. Only the web server runs it, not the command-line mode.
- The app may SELECT, INSERT, and DELETE claims, never UPDATE them. A trigger also rejects an UPDATE by any role, to catch mistakes.

**The permanent backstop stays:** `UNIQUE (client_id, idempotency_key)` on `transfers` and `fundings`. Even if every claim were deleted, by the cleanup or a bug, a retry gets 409 and never moves money twice.

## Consequences
- **A retry is now safe in the full sense:** the same key and body return the original response, whether the retries come one after another or all at once, and the money moves once.
- **A retry of a failed request runs again,** and can succeed if things changed (for example, the account was funded in between). A client that wants a fresh attempt to be distinct uses a new key.
- **A changed body after a failure isn't detected,** because the failed request never took the key.
- **409 now has two problem types:** `duplicate-request` (done; its claim expired) and `request-in-progress` (not finished yet). A client must branch on `type`, not on the status alone, or it could treat an unfinished request as done.
- **A replay shows the current response format.** If a deploy changes the shape within the retention window, a replay looks like a `GET` would.
- **The fingerprint encoding is a contract with stored claims.** Changing it, or the fields a command contributes, turns retries that span the deploy into 422s. The version tag and the pinned test make that deliberate.
- **The app can delete claims.** A compromised app could cost clients their replays, never money: the business tables' unique keys still stop a second debit.
- **Every money-moving request costs one more INSERT,** measured by `ConcurrencyIT`.
- **No new deadlocks:** a transaction takes exactly one claim, always before any account lock, so it never holds an account while waiting for a claim.

## How to explain it
"The first statement of every money-moving transaction claims the idempotency key with an insert that does nothing on conflict, so the claim and the money movement commit together or not at all. A retry with the same body replays the original result, rebuilt from the append-only transfer row; a different body is a 422; and a retry that arrives while the first is still running waits up to the lock timeout, then gets a 409 saying it's in progress. Expired claims are deleted after 24 hours, and the unique key on the transfer row still stops a double debit after that."
