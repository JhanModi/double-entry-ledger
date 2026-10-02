# ADR-0010: In-process mock bank and payment recovery

- **Status:** Accepted
- **Date:** 2026-10-01

## Context
Payments enter and leave the system through an external bank. The bank can be slow, decline, succeed and *then* time out, never respond, or send the same callback twice. Calls to it can't be part of our database transaction.

## Options considered
- **Separate mock-bank service over HTTP.** Real network behavior, but tests become non-deterministic and there's more infrastructure to run.
- **In-process mock behind a `BankRail` interface, with scriptable faults.** Deterministic, and every failure can be reproduced on demand.
- **A real sandbox** (e.g., Stripe test mode). Realistic, but we can't script the failure modes we need, and it adds an external dependency.

## Decision
**`BankRail` interface:**
- `submit(instructionId, payment)`. `instructionId` is the bank's idempotency key, so resubmitting it is safe.
- `getStatus(instructionId)`.
- Settlement notifications (callbacks).
- A daily statement CSV, used by reconciliation (M11).

**Scriptable faults:** delay, decline, timeout-after-success, never-responds, duplicate callback.

**Recovery pattern:**
1. Persist the payment, its hold, and `bank_instructions(status=QUEUED)` in one transaction, then commit.
2. Call the bank *after* the commit.
3. Record the result with a conditional transition (`UPDATE … WHERE status = :expected`, exactly one row affected).

**Rules:**
- **Timeouts:** a timeout marks the instruction UNKNOWN and keeps the hold. A timeout **never** marks the payment FAILED, because the bank may have paid.
- **Sweeper:** re-drives QUEUED and UNKNOWN instructions. It queries the bank's status first, then resubmits with the same instruction id, using exponential backoff (`attempts`, `next_attempt_at`).
- **Retry limit:** after `max_attempts`, the payment moves to **NEEDS_REVIEW**, with the hold still ACTIVE.
- **Admin resolution:** an admin resolves NEEDS_REVIEW to SETTLED or FAILED. It requires the admin scope and a reason, is audit-logged, and is based on bank status or reconciliation evidence.
- **Late settlement:** a settlement that arrives for a FAILED payment is never dropped. It's recorded as an exception, and reconciliation flags it.

## Consequences
- Every crash point has a defined, tested outcome.
- Tests are deterministic.
- The mock isn't a real network. A separate-service version could be a later extension.
- `max_attempts`, backoff, and hold lifetime are tuning parameters, chosen in M9b.

## How to explain it
"Bank calls happen outside the database transaction, so I persist the intent first and call the bank with my own instruction id as its idempotency key. If the call times out, the payment becomes 'unknown,' not 'failed,' because the bank may have paid. A sweeper retries safely, and after a bounded number of attempts it hands the payment to a person instead of guessing."
