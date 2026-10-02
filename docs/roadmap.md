# Roadmap

Each milestone opens with a short proposal (the change-control format in `CLAUDE.md`). It closes when `mvn verify` passes locally and in CI, the invariant checker passes, and the owner has completed the teach-back questions.

Status: ✅ done · 🔍 in review · ⏳ not started

## Current work: M4a (checkpoint 2026-10-02)

### Finished
- **Code:**
  - **V4 migration:** `api_clients`; `api_keys` (hash-only); `accounts.client_id`, with a CHECK and the identity trigger extended so an account's owner never changes; grants for the new tables.
  - **`clients` module:** key format, constant-time verifier, client service, the `clients create` command-line bootstrap, scopes.
  - **`web` module:** deny-by-default security rules, bearer-key filter, Problem Details for 401/403 and everything else, the accounts controller.
  - **Ledger:** owner-scoped lookup via `LedgerQueries.accountOwnedBy`.
- **Tests:** `./mvnw verify` is green: 73 unit tests, 90 integration tests, and the Spotless check.
- **Planted-bug checks** (in a scratchpad copy): ignoring revocation, leaky ownership SQL, and `authenticated()` in place of `denyAll()`. Each was caught by its own test.
- **Local end-to-end:**
  - The CLI created two clients.
  - `curl` returned 201 on create and 200 on read, a 404 for the other client's account identical to a random id's, 401 without a key, and 403 for `/actuator/env` with a key.
  - No key secrets appeared in the logs or the database.
- **Fixed during verification:**
  - The command-line mode failed to start, because the security config needs a web server. Fixed with `@ConditionalOnWebApplication`; `ClientsCommandIT` now runs without a web server.
  - Spring Boot's default generated-password user is excluded.
- **Docs:** ADR-0016, ADR-0017, primer 03, design §8 and the failure-modes table, glossary, the README's "Use the API" section, and the CLAUDE.md API rules and permanent rules.

### Left to close M4a
1. ✅ **Committed** as `a2d7803`. The checkpoint edits to `CLAUDE.md` and `docs/roadmap.md` came after it and still need a commit.
2. ⏳ **CI green:** waiting for the owner to confirm (Claude can't see GitHub).
3. ✅ **Teach-back answered** (all five correct, feedback given):
   1. What's the difference between a 401 and a 403 in this API? Give one request that gets each.
   2. Why does the verifier compare hashes with `MessageDigest.isEqual`, and why does it still compare against a dummy hash when the key id doesn't exist?
   3. Why is CSRF protection turned off, and what would have to change about the API for that to become unsafe?
   4. Bob requests Alice's account id. Walk through what happens, and explain why he gets a 404 rather than a 403.
   5. Why does the first API key come from a command-line mode instead of an HTTP endpoint? What bug did the end-to-end run catch, and why did the original test miss it?
4. **Then** mark M4a ✅, give feedback on the answers, and post the **M4b proposal** (row below). M4b must also state clearly that transfers aren't safe to retry until M6 (idempotency).

### Failing tests
None. Known gaps that aren't failures:
- **Constant-time comparison** is verified by code review, not by a test, because timing tests are unreliable.
- **Mockito prints a "self-attaching" warning** during integration tests. It's harmless today but will break on a future JDK. Fix it later by adding Mockito as a Java agent in the Surefire/Failsafe `argLine`.

### Decisions made this session that aren't recorded elsewhere
- **Invalid credentials are always 401.** An `Authorization` header with an invalid key gets 401 even on public paths such as `/actuator/health`. Only a request with *no* header is treated as anonymous.
- **Actuator endpoints other than health are never reachable:** 401 without a key, 403 with one (`denyAll`).
- **History paging:** the page size defaults to 50, with a maximum of 100, and the cursor is the id of the last entry returned.
- **Unknown JSON fields are ignored** (Spring Boot's default). That's how a `clientId` in a request body gets harmlessly dropped. M4b should decide whether to reject unknown fields instead.
- **`spring-boot-starter-security-test` is on the test classpath but unused,** since tests authenticate over real HTTP with real keys. Keep or remove it in M4b.
- **The invariant checker's SQL,** planned as an M3a owner exercise, was written by Claude at the owner's request. The owner later made "Claude writes all code" a permanent rule.

## Tier 1: a strong portfolio piece on its own

| | Milestone | Scope |
|---|---|---|
| ✅ | **M0 Decisions & repo** | git init, `.gitignore`, `.env.example`; CLAUDE.md decisions; ADRs 0001–0012; design doc skeleton, glossary, roadmap, primer. No app code. |
| ✅ | **M1 Walking skeleton** | Maven project, Docker Compose (Postgres), Flyway baseline, Actuator health check, one Testcontainers test, GitHub Actions CI, Dependabot, Spotless. |
| ✅ | **M2 Money** | `Money`/currency types, overflow-safe arithmetic, allocation (`allocate()` written by the owner), rounding; unit and property tests; ArchUnit "no floats" rule. |
| ✅ | **M3a Ledger schema & posting** | Accounts, ledger transactions, entries; DB guards (constraints, deferred balance triggers, append-only triggers); posting service; balance and history reads; invariant checker. |
| ✅ | **M3b Least-privilege DB roles** | `ledger_app` group role with column-level grants (V3 written by the owner); the app connects as a restricted login and Flyway as the owner, in local runs and tests. |
| 🔍 | **M4a Clients, API keys, accounts API** | `api_clients` and `api_keys`; key format, hashing, and constant-time verification; CLI to create a client and key; Spring Security filter, scopes, deny-by-default; tenant isolation (other tenants' accounts are 404); Problem Details errors; open/read accounts, balances, and history over HTTP. Implemented; awaiting CI and teach-back. |
| ⏳ | **M4b Transfers, funding, audit log** | Same-client transfers; admin funding from seeded bank-settlement accounts; business errors as Problem Details; append-only audit log and request ids; OpenAPI; rate-limiting decision; reject non-integer amounts. |
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
| Rate-limiting library | M4b |
| License; whether `CLAUDE.md` and `docs/learning/` stay in the public repo | M7 |
| Negative-balance policy for forced reversals (default proposal: shortfall to a customer-receivables account) | M8 |
| `max_attempts`, backoff, hold lifetime | M9b |
| Public deployment and budget | M16 |
| Run migrations as a separate deployment step, so the app process never holds owner credentials (ADR-0015) | M16 |
