# ADR-0006: Authorization holds in a holds table, with expiry

- **Status:** Accepted
- **Date:** 2026-10-01

## Context
"Authorized" means funds are reserved for an outbound payment but haven't moved yet. The reservation must reduce what the customer can spend, without posting entries, because no money has moved.

## Options considered
- **Holds table + `held_balance`.** An authorization isn't a posting, which is how card networks work.
- **Entries into a suspense account.** Fully double-entry, but every release becomes a reversal, and the shared suspense account becomes a hot row.
- **Pending vs. posted entry states.** Changing an entry's status breaks append-only. Keeping separate immutable records instead amounts to a holds table under another name.

## Decision
- **Tables:** `holds(id, account_id, payment_id UNIQUE, amount, currency, status ACTIVE|CAPTURED|RELEASED, expires_at)`, with an append-only `hold_events` history.
- **Available balance** = `posted_balance − held_balance`.
- **Authorize:** lock the account, check available ≥ amount, insert an ACTIVE hold, then `held_balance += amount`.
- **Capture (settle):** one transaction. Conditionally move the hold ACTIVE→CAPTURED, post the entries, then `posted_balance -= amount` and `held_balance -= amount`.
- **Release:** conditionally move ACTIVE→RELEASED, then `held_balance -= amount`.
- **Expiry (safety condition).** The recovery sweeper releases an expired hold (payment → FAILED, reason EXPIRED) **only if the bank cannot have acted on it.** That means its bank instruction is still QUEUED, and in the same transaction the instruction is moved to CANCELLED so it can never be sent.
  - If the instruction is SENT, UNKNOWN, or ACKED, the bank may already have paid. The payment moves to NEEDS_REVIEW, and the hold stays ACTIVE.
- **Hold lifetime** must be longer than the bank's normal settlement window. The values are chosen in M9b.

## Consequences
- Matches card-network semantics, and entries stay strictly append-only.
- Holds live outside double-entry, so they need their own history (`hold_events`) and their own invariant: `held_balance` = sum of ACTIVE holds.
- A badly chosen hold lifetime floods the review queue. It's a tunable parameter, and the queue size is exposed as a metric (M14).

## How to explain it
"Authorizing a payment places a hold that lowers the available balance without moving money. Settlement captures the hold and posts the entries in one transaction. An expired hold is released only if the instruction never reached the bank. Otherwise a person reviews it, because releasing funds the bank may already have sent would let the customer spend them twice."
