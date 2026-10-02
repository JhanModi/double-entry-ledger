# ADR-0019: Interim idempotency until M6

- **Status:** Accepted
- **Date:** 2026-10-02

## Context
ADR-0007's full idempotency design (claim the key first, hash the request, replay the stored response) is M6's work. But M4b ships endpoints that move money. Without any protection, a client that retries after a timeout moves the money twice. `CLAUDE.md` also requires an idempotency key on every endpoint that changes money state.

## Options considered
- **Nothing until M6.** Simplest, but a retry after a timeout moves the money twice.
- **Require an `Idempotency-Key` now, and enforce it with a unique constraint on the business row.** A duplicate is rejected with 409. M6 adds replay on top.
- **Build all of ADR-0007 now.** Correct, but it's M6's whole scope and would roughly double M4b.

## Decision
- **The header:** `POST /v1/transfers` and `POST /v1/fundings` require `Idempotency-Key`: 1 to 255 characters from `A-Z a-z 0-9 _ . : -`. A missing or malformed key gets 400.
- **Where it's stored:** on the transfer or funding row, with `UNIQUE (client_id, idempotency_key)`.
  - Keys are scoped to the client, so two clients can use the same key.
  - Until M6, keys are also scoped to the kind of movement: a transfer and a funding may share a key. M6's single idempotency table will unify them.
- **The flow, inside one transaction:**
  1. Look up the client's key. If it exists, answer 409 `duplicate-request` with the original's id, and do nothing.
  2. Otherwise, post the ledger transaction and insert the business row.
  3. If another request with the same key committed in the meantime, the insert fails on the unique index. The whole transaction rolls back, and the answer is 409 with the original's id.
- **M6 keeps this constraint** as the permanent backstop that ADR-0007 already plans. M6 adds the claim-first table, request hashing, and stored responses.

## Consequences
- **Money never moves twice because of a retry,** whether the retries are sequential or simultaneous. The database guarantees it.
- **The API contract won't change in M6:** the header is required from now on.
- **Transfers aren't fully safe to retry until M6:**
  1. A retry gets 409 with the original's id, not the original response. The client fetches the result with `GET`.
  2. The same key with a different body also gets 409, not M6's 422.
  3. If two identical requests arrive at the same moment, the second waits for the first. If the first spent the money it needed, the second gets 422 insufficient funds instead of 409, because the balance is checked before the key's uniqueness is. It's still never applied twice. M6 fixes this by claiming the key first.

## How to explain it
"Every money-moving request carries an idempotency key, and there's a unique constraint on client and key in the transfers table. So even two identical requests racing each other can't both commit, and the database guarantees it. For now a duplicate gets a 409 pointing at the original. The next step is replaying the original response, which needs the key claimed at the start of the transaction."
