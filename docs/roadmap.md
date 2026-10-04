# Roadmap

The project is built in small milestones, in order. Each opens with a written proposal and closes when `./mvnw verify` passes locally and in CI, the invariant checker passes, and the milestone's teach-back questions are answered. The working notes behind each one (tests at close, planted-bug checks, measured numbers, teach-back records) are in the [milestone log](milestone-log.md).

Status: ✅ done · 🚧 in progress · 🔍 in review · ⏳ not started

**Now:** M7, the resume checkpoint, is implemented and in review. **Next:** M8, reversals.

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
| 🔍 | **M7 Resume checkpoint** | README rewritten as the front page, with a CI badge; architecture doc with diagrams (v1); demo script and a captured run; hand-written OpenAPI spec checked against the code, and linted in CI (ADR-0024); MIT license and `SECURITY.md` (ADR-0025); gitleaks scan of the full history; roadmap split from the milestone log. Repo made public (owner's call). Described as a *ledger and transfers API*. |
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

## Known gaps

Carried forward on purpose, each with where it gets fixed. None is a failing test.

- **No rate limiting yet** (M15b). The authentication and money-moving endpoints can be called without limit, so the app must not be deployed publicly before M15b.
- **Nothing checks that a transfer or funding row's amount matches its ledger entries.** Both are written together by the same code, but the invariant checker doesn't compare them yet (ADR-0018).
- **Nothing checks that every claim has its transfer or funding row.** They're committed together, and `ConcurrencyIT` counts them one to one, but the invariant checker doesn't compare them.
- **The invariant checker runs only in tests.** A scheduled check with an alert belongs with metrics (M14).
- **Pool exhaustion:** a request that can't get a database connection waits up to Hikari's 30 seconds and then gets a 500. Pool sizing, `statement_timeout`, and `idle_in_transaction_session_timeout` belong with load testing (M15).
- **The 1,000-request test accepts 503s and 409 `request-in-progress`,** so on its own it can't tell a broken lock order from a busy one. The no-retry deadlock test covers that, as M5's planted-bug check showed. None of either appeared in the measured runs.
- **One branch of the claim is reviewed, not tested:** if the cleanup deletes an expired claim between the claim's insert and its read, the claim is tried again. That window can't be forced without a test hook in the code.
- **The idempotency cleanup has no metric or alert.** A cleanup that stopped running would only show as a growing table. Metrics arrive in M14.
- **Key revocation isn't audited.** `ClientService.revokeKey` has no caller outside tests yet. When a revoke command is built, it must record an `API_KEY_REVOKED` action in the same transaction. That needs a migration, because the allowed actions are a database CHECK (`audit_log_action_known`).
- **Constant-time comparison** is verified by code review, not by a test, because timing tests are unreliable.
- **Spring's own errors other than 400 have no problem type.** A 415 (a body not sent as JSON), for example, is Problem Details with a `requestId` but no `type`, which RFC 9457 reads as `about:blank`. Only 400s are rewritten to `invalid-request` today. Found in M7; the spec says so.
- **What `OpenApiSpecIT` can't see:** status codes, problem types, and descriptions in `docs/openapi.yaml` are kept true by review against the API tests (ADR-0024).
- **The demo script has only been run in Git Bash on Windows.** It's written for bash, curl, and jq on macOS and Linux too, but hasn't been run there. macOS's built-in bash 3.2 is untested.
- **The V5 migration's comment says "Append-only, for every role".** It overstates what triggers do (see ADR-0020), but V5 is applied, and editing it would change its Flyway checksum. The ADR, glossary, and tests carry the correct wording.
- **Mockito prints a "self-attaching" warning** during integration tests. It's harmless today but will break on a future JDK. Fix it later by adding Mockito as a Java agent in the Surefire/Failsafe `argLine`.

## Decisions still open

| Decision | Milestone |
|---|---|
| Negative-balance policy for forced reversals (default proposal: shortfall to a customer-receivables account; an `allow_negative` flag on accounts is another option, see ADR-0005) | M8 |
| Operator identity for cross-tenant admin actions (reversals, resolving NEEDS_REVIEW). Today an `admin` key acts only on its own client's accounts (ADR-0018). | M8 |
| Rate-limiting library | M15b (before any public deployment) |
| `max_attempts`, backoff, hold lifetime | M9b |
| Public deployment and budget | M16 |
| Run migrations as a separate deployment step, so the app process never holds owner credentials (ADR-0015) | M16 |

Decided in M7: the license (MIT) and what the public repository contains (everything, history included), in [ADR-0025](adr/0025-license-and-public-repository-contents.md).
