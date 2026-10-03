# Roadmap

Each milestone opens with a short proposal (the change-control format in `CLAUDE.md`). It closes when `mvn verify` passes locally and in CI, the invariant checker passes, and the owner has completed the teach-back questions.

Status: ✅ done · 🚧 in progress · 🔍 in review · ⏳ not started

## Current work: M6 Idempotency (proposal next)

**M5 closed on 2026-10-03:**
- Committed as `47b1c5f`, and CI is green on it. The checkpoint record (decisions D1-A to D5-A, tests first, planted-bug checks, measured numbers) is in this file as of `47b1c5f`. In short:
  - Postings lock, then check, then write, with a 2-second lock timeout. Deadlocks are retried, and a busy account is a 503.
  - `ConcurrencyIT` ran 1,000 requests in 3.6 to 4.5 seconds, with the invariant checker clean throughout.
  - 20 planted bugs, all caught.
- **Approved after the fact:** an untranslated `CHECK` violation is a 500, because it now means the check under the lock is buggy. ADR-0020 carries a "Corrected" line for the trigger wording.
- **Teach-back:** all five answers were correct. Refinements given:
  - Q1: in the test, the transaction that closed the account was the one holding the lock. The transfer's balance `UPDATE` waited for exactly that close, then applied its delta, because a delta doesn't re-check status. Existence is also safe to read without a lock: accounts are never deleted.
  - Q3: with a 500 ms timeout, Postgres would never run its deadlock check, so the "deadlock detected" log line would never appear either.
  - Q4: in Postgres, an aborted transaction can't "appear to succeed": every later statement fails (`25P02`) until it rolls back. The silent-loss risk exists only with savepoints, or in databases that roll back just the failed statement.
  - Q5: the 500 is logged in full with its request id today; alerting on it arrives with metrics in M14.

### Known gaps carried forward (not failures)
- **The V5 migration's comment says "Append-only, for every role".** It overstates what triggers do (see ADR-0020), but V5 is applied, and editing it would change its Flyway checksum. The ADR, glossary, and tests carry the correct wording.
- **The 1,000-request test accepts 503s,** so on its own it can't tell a broken lock order from a busy one. The no-retry deadlock test covers that, as the planted-bug check showed.
- **Pool exhaustion:** a request that can't get a database connection waits up to Hikari's 30 seconds and then gets a 500. Pool sizing, `statement_timeout`, and `idle_in_transaction_session_timeout` belong with load testing (M15).
- **Same-key waits count as lock waits.** Until M6, the second of two same-key requests can get 503 instead of 409 if the first takes over 2 seconds to commit (ADR-0022).
- **Nothing checks that a transfer or funding row's amount matches its ledger entries.** Both are written together by the same code, but the invariant checker doesn't compare them yet (ADR-0018).
- **Local database:** V5 is applied locally, so it must never be edited; new schema changes go in V6.
- **Key revocation isn't audited.** `ClientService.revokeKey` has no caller outside tests yet. When a revoke command is built, it must record an `API_KEY_REVOKED` action in the same transaction. That needs a migration, because the allowed actions are a database CHECK (`audit_log_action_known`).
- **Constant-time comparison** is verified by code review, not by a test, because timing tests are unreliable.
- **Mockito prints a "self-attaching" warning** during integration tests. It's harmless today but will break on a future JDK. Fix it later by adding Mockito as a Java agent in the Surefire/Failsafe `argLine`.

## Tier 1: a strong portfolio piece on its own

| | Milestone | Scope |
|---|---|---|
| ✅ | **M0 Decisions & repo** | git init, `.gitignore`, `.env.example`; CLAUDE.md decisions; ADRs 0001–0012; design doc skeleton, glossary, roadmap, primer. No app code. |
| ✅ | **M1 Walking skeleton** | Maven project, Docker Compose (Postgres), Flyway baseline, Actuator health check, one Testcontainers test, GitHub Actions CI, Dependabot, Spotless. |
| ✅ | **M2 Money** | `Money`/currency types, overflow-safe arithmetic, allocation (`allocate()` written by the owner), rounding; unit and property tests; ArchUnit "no floats" rule. |
| ✅ | **M3a Ledger schema & posting** | Accounts, ledger transactions, entries; DB guards (constraints, deferred balance triggers, append-only triggers); posting service; balance and history reads; invariant checker. |
| ✅ | **M3b Least-privilege DB roles** | `ledger_app` group role with column-level grants (V3 written by the owner); the app connects as a restricted login and Flyway as the owner, in local runs and tests. |
| ✅ | **M4a Clients, API keys, accounts API** | `api_clients` and `api_keys`; key format, hashing, and constant-time verification; CLI to create a client and key; Spring Security filter, scopes, deny-by-default; tenant isolation (other tenants' accounts are 404); Problem Details errors; open/read accounts, balances, and history over HTTP. |
| ✅ | **M4b Transfers, funding, audit log** | Same-client transfers; admin funding of the client's own accounts from seeded bank-settlement accounts; business errors as Problem Details; append-only audit log and request ids; interim idempotency (`Idempotency-Key` plus a unique constraint, 409 on a duplicate); per-currency amount limits; strict JSON (integer amounts only, unknown fields rejected); ArchUnit module rules. |
| ✅ | **M5 Concurrency** | Ordered locking, `lock_timeout`, retries; 1,000-request and deadlock tests, with the invariant checker (built in M3a) run under concurrency. |
| ⏳ | **M6 Idempotency** | Claim/replay in the same transaction, request hashing, expiry cleanup; same-key concurrency tests. |
| ⏳ | **M7 Resume checkpoint** | README, short design doc, architecture diagram v1, demo script; OpenAPI docs for the public demo (a new dependency, so it needs approval); gitleaks history scan, license; repo made public (owner's call). Described as a *ledger and transfers API*. |
| ⏳ | **M8 Reversals** | Admin reversal, `UNIQUE(reverses_txn_id)`, negative-balance policy. |

## Tier 2: completes all seven features

| | Milestone | Scope |
|---|---|---|
| ⏳ | **M9a Payments + holds** | 6-state machine incl. NEEDS_REVIEW, conditional transitions, holds with `expires_at`, status history, synchronous mock bank. |
| ⏳ | **M9b Bank rail & recovery** | Async mock bank with faults, `bank_instructions` with backoff and max retries → NEEDS_REVIEW, hold-expiry sweeper, admin resolution. |
| ⏳ | **M10 Outbox** | Same-transaction events, single relay, `EventPublisher` interface. |
| ⏳ | **M11 Reconciliation** | Statement CSV generator with injectable discrepancies, scheduled matching, results API. |
| ⏳ | **M12 Multi-currency & FX** | Rates, quotes, 4-entry conversions, spread, rounding. |

## Tier 3: production polish

| | Milestone | Scope |
|---|---|---|
| ⏳ | **M13 Kafka + webhooks** | Kafka (KRaft) in Compose, publisher, signed-webhook consumer with dedupe. |
| ⏳ | **M14 Observability** | Business metrics, Prometheus, Grafana dashboards. |
| ⏳ | **M15 Load testing** | k6 scenarios, documented hardware, invariant check after each run, measured numbers in the README. |
| ⏳ | **M15b Rate limiting** | Per-client limits on authentication and money-moving endpoints, with 429 Problem Details. **Required before any public deployment (M16).** Until then, `CLAUDE.md`'s rate-limiting rule is knowingly unmet; the app is not deployed. |
| ⏳ | **M16 Final docs & demo** | Final diagram and design doc, interview Q&A, demo video; optional public deploy. |

## Decisions still open

| Decision | Milestone |
|---|---|
| License; whether `CLAUDE.md` and `docs/learning/` stay in the public repo | M7 |
| Negative-balance policy for forced reversals (default proposal: shortfall to a customer-receivables account; an `allow_negative` flag on accounts is another option, see ADR-0005) | M8 |
| Operator identity for cross-tenant admin actions (reversals, resolving NEEDS_REVIEW). Today an `admin` key acts only on its own client's accounts (ADR-0018). | M8 |
| Rate-limiting library | M15b (before any public deployment) |
| `max_attempts`, backoff, hold lifetime | M9b |
| Public deployment and budget | M16 |
| Run migrations as a separate deployment step, so the app process never holds owner credentials (ADR-0015) | M16 |
