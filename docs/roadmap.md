# Roadmap

Each milestone opens with a short proposal (the change-control format in `CLAUDE.md`). It closes when `mvn verify` passes locally and in CI, the invariant checker passes, and the owner has completed the teach-back questions.

Status: ✅ done · 🚧 in progress · 🔍 in review · ⏳ not started

## Current work: M7 Resume checkpoint (proposal next)

**M6 closed on 2026-10-03:**
- Committed as `cd11a2e`, and CI is green on it. The checkpoint record (decisions D1-A to D5-A, tests first, planted-bug checks, measured numbers) is in this file as of `cd11a2e`. In short:
  - Every money-moving transaction claims its idempotency key first, with `INSERT … ON CONFLICT DO NOTHING`, so the claim commits or rolls back with the money movement (ADR-0023).
  - **Same key and request:** the original response is replayed, with `Idempotent-Replayed: true`.
  - **Same key, different request:** 422.
  - **A duplicate still running after 2 seconds:** 409 `request-in-progress`.
  - **After expiry:** 409 `duplicate-request`; the movement rows' unique keys are the permanent backstop.
  - `ConcurrencyIT` ran 1,000 requests in 3.87 to 4.08 seconds (M5: 3.6 to 4.5), with 77 to 99 replays per run and the invariant checker clean throughout.
  - 152 unit and 236 integration tests; 37 planted bugs, all caught.
- **Done differently from the proposal, and reported at the time:**
  - The two new problem types moved from checkpoint 3 to checkpoint 2, so no commit returned 500s for them.
  - A never-change trigger was added on `idempotency_keys`.
  - `RequestInProgressException` carries no cause, instead of `RetryingTransactions` being edited.
  - Checkpoint 3's tests weren't written first; its planted-bug check shows they can fail.
- **Teach-back:** all five answers were correct. Refinements given:
  - Q1: the two lock timeouts run side by side. If the first request is itself waiting on a busy account, the duplicate's own 2 seconds on the claim can run out first. Then the duplicate gets 409 `request-in-progress` while the first ends with 503. A later retry with the same key still gets the right result.
  - Q2: the insert can see the conflict even though a select in the same statement can't. Conflict detection checks the unique index against the latest committed state (and waits for in-progress rows); it doesn't use the statement's snapshot.
  - Q3: the key isn't a security control.
    - Replaying a captured request needs the client's API key. An attacker who has that can simply send new requests with new keys; revocation and rate limits (M15b) are the defence there.
    - SQL injection as `ledger_service` could also insert new balanced postings, so deleting claims isn't the worst it could do. The real defence is that every query is parameterized.
  - Q4: the cost of the choice is that the underlying SQL error isn't kept on the exception. That's acceptable, because the message says exactly what happened. The more general fix would be for `RetryingTransactions` to classify only raw database exceptions; M6 didn't change the retry layer.
  - Q5: the version tag is hashed, not stored, so today the server can't tell which version a stored claim used. Supporting two versions during a change needs one of two things:
    - a `fingerprint_version` column (a migration); or
    - computing both the old and the new fingerprint for a retry, and accepting a match on either until the retention period has passed.

### Known gaps carried forward (not failures)
- **The V5 migration's comment says "Append-only, for every role".** It overstates what triggers do (see ADR-0020), but V5 is applied, and editing it would change its Flyway checksum. The ADR, glossary, and tests carry the correct wording.
- **The 1,000-request test accepts 503s,** so on its own it can't tell a broken lock order from a busy one. The no-retry deadlock test covers that, as the planted-bug check showed.
- **Pool exhaustion:** a request that can't get a database connection waits up to Hikari's 30 seconds and then gets a 500. Pool sizing, `statement_timeout`, and `idle_in_transaction_session_timeout` belong with load testing (M15).
- **Nothing checks that a transfer or funding row's amount matches its ledger entries.** Both are written together by the same code, but the invariant checker doesn't compare them yet (ADR-0018).
- **Nothing checks that every claim has its transfer or funding row.** They're committed together, and `ConcurrencyIT` counts them one to one, but the invariant checker doesn't compare them.
- **One branch of the claim is reviewed, not tested:** if the cleanup deletes an expired claim between the claim's insert and its read, the claim is tried again. That window can't be forced without a test hook in the code.
- **The 1,000-request test accepts 409 `request-in-progress`,** as it accepts 503s: on a slow machine a repeat may wait for its original longer than the lock timeout. None appeared in the measured runs.
- **The cleanup has no metric or alert.** A cleanup that stopped running would only show as a growing table. Metrics arrive in M14.
- **Local database:** V5 is applied locally, and V6 is committed (it's applied the next time the app runs locally). Neither may ever be edited; new schema changes go in V7.
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
| ✅ | **M6 Idempotency** | Claim/replay in the same transaction, request hashing, expiry cleanup; same-key concurrency tests. |
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
