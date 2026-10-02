# Roadmap

Each milestone opens with a short proposal (the change-control format in `CLAUDE.md`). It closes when `mvn verify` passes locally and in CI, the invariant checker passes, and the owner has completed the teach-back questions.

Status: ✅ done · 🔍 in review · ⏳ not started

## Tier 1: a strong portfolio piece on its own

| | Milestone | Scope |
|---|---|---|
| ✅ | **M0 Decisions & repo** | git init, `.gitignore`, `.env.example`; CLAUDE.md decisions; ADRs 0001–0012; design doc skeleton, glossary, roadmap, primer. No app code. |
| ✅ | **M1 Walking skeleton** | Maven project, Docker Compose (Postgres), Flyway baseline, Actuator health check, one Testcontainers test, GitHub Actions CI, Dependabot, Spotless. |
| ✅ | **M2 Money** | `Money`/currency types, overflow-safe arithmetic, allocation (`allocate()` written by the owner), rounding; unit and property tests; ArchUnit "no floats" rule. |
| ✅ | **M3a Ledger schema & posting** | Accounts, ledger transactions, entries; DB guards (constraints, deferred balance triggers, append-only triggers); posting service; balance and history reads; invariant checker. |
| 🔍 | **M3b Least-privilege DB roles** | `ledger_app` group role with column-level grants (V3 written by the owner); the app connects as a restricted login and Flyway as the owner, in local runs and tests. Implemented; awaiting CI and teach-back. |
| ⏳ | **M4 Transfers API + auth** | API-key filter, scopes, tenant isolation, customer vs. system accounts, admin funding, Problem Details, OpenAPI, audit log. Open question: JPA for non-ledger tables. |
| ⏳ | **M5 Concurrency** | Ordered locking, `lock_timeout`, retries; 1,000-request and deadlock tests, with the invariant checker (built in M3a) run under concurrency. |
| ⏳ | **M6 Idempotency** | Claim/replay in the same transaction, request hashing, expiry cleanup; same-key concurrency tests. |
| ⏳ | **M7 Resume checkpoint** | README, short design doc, architecture diagram v1, demo script; gitleaks history scan, license; repo made public (owner's call). Described as a *ledger and transfers API*. |
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
| ⏳ | **M16 Final docs & demo** | Final diagram and design doc, interview Q&A, demo video; optional public deploy. |

## Decisions still open

| Decision | Milestone |
|---|---|
| Spring Data JPA for simple non-ledger tables? ([ADR-0008](adr/0008-jdbcclient-with-hand-written-sql.md)) | M4 |
| Rate-limiting library | M4 |
| License; whether `CLAUDE.md` and `docs/learning/` stay in the public repo | M7 |
| Negative-balance policy for forced reversals (default proposal: shortfall to a customer-receivables account) | M8 |
| `max_attempts`, backoff, hold lifetime | M9b |
| Public deployment and budget | M16 |
| Run migrations as a separate deployment step, so the app process never holds owner credentials (ADR-0015) | M16 |
