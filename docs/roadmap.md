# Roadmap

Each milestone opens with a short proposal (the change-control format in `CLAUDE.md`). It closes when `mvn verify` passes locally and in CI, the invariant checker passes, and the owner has completed the teach-back questions.

Status: ✅ done · 🚧 in progress · 🔍 in review · ⏳ not started

## Current work: M5 Concurrency (approved 2026-10-02)

**M4b closed on 2026-10-02:**
- All five checkpoints are committed (`f7a8ce3` to `2700cde`), and CI is green on `2700cde`. The checkpoint record (tests, planted-bug checks, the end-to-end run) is in this file as of `2700cde`.
- **Teach-back:** all five answers were correct. Refinements given:
  - Q1: in M6 the unique constraint stays, as the permanent backstop once stored responses expire.
  - Q2: identical requests wait at the balance row lock; the unique-index wait applies when the same key comes with different accounts.
  - Q4: the dropped unknown field was the same kind of silent bug.

**Starting point:** M4b's code already couldn't overdraw an account, and transfers couldn't deadlock: balances change by SQL deltas applied in ascending id order, and the `CHECK` backstop rejects an overdraft. What it lacked:
1. **A wait limit.** Without `lock_timeout`, a request waits forever for a lock someone else holds, keeping one of the pool's 10 connections.
2. **Recovery.** A deadlock would reach the client as a 500.
3. **Checks on locked data.** Status was read without a lock, and insufficient funds was found by matching the constraint's name in Postgres's error message.
4. **Proof under load.** Only M4b's smoke tests existed.

The proposal was approved on 2026-10-02 with these decisions:
- **D1-A, lock, then check, then write (ADR-0005):** a posting first locks its customer accounts (`SELECT … ORDER BY id FOR NO KEY UPDATE`), re-reads status and balances under the lock, and checks funds in Java before writing anything. System accounts are never locked. The `CHECK` stays as the backstop.
- **D2-A, `lock_timeout`:** set per transaction at the start of each posting with `set_config('lock_timeout', …, true)`. The value comes from configuration and defaults to **2 seconds**, longer than Postgres's 1-second `deadlock_timeout`.
- **D3-A, retries:** a small hand-written helper retries the whole transaction on deadlock (`40P01`) or serialization failure (`40001`), recognized by SQLSTATE. At most 3 attempts, with a short random backoff, and only when the helper started the transaction. A lock timeout is never retried.
- **D4-A, the busy response:** 503 `/problems/account-busy` with `Retry-After: 1`. Nothing moved, and retrying with the same `Idempotency-Key` is safe.
- **D5-A, test level:** the 1,000-request test calls the services directly; one HTTP test covers the 503. HTTP load testing is k6's job in M15.

### Left to close M5
1. ⏳ **Commit** (the owner).
2. ⏳ **CI green:** waiting for the owner to confirm (Claude can't see GitHub).
3. ⏳ **Teach-back** answered.

### Checkpoints (`./mvnw verify` passes after each, plus a planted-bug check)
Each planted-bug check ran in a scratchpad copy of the project, never in the working tree.

1. ✅ **Locking in the posting service:**
   - **`PostingService.post`** now runs: limit lock waits, read what never changes, lock the customer accounts (`AccountRepository.lockForPosting`: one statement, `ORDER BY id FOR NO KEY UPDATE`, customers only), check under the lock (`BalanceChanges`, pure), then write.
   - **`lock_timeout`:** `set_config('lock_timeout', …, true)`, from `ledger.lock-timeout` (default 2s; zero refused at startup). A timeout becomes `AccountBusyException`.
   - **The `CHECK` backstop's error is no longer translated** into insufficient funds. If it fires, the check under the lock is wrong, which must surface as a 500.
   - **Tests first:** `PostingLocksIT` ran against M4b's code first. 3 of its 5 tests failed:
     - The transfer **credited an account closed while it waited.**
     - A held lock made the posting **hang for the full 30-second test limit.**
     - `lock_timeout` was `0`, meaning no limit.

     Lock order and "system accounts never locked" already passed, as the proposal said.
   - **ADR-0022** written.
   - **Tests:** `./mvnw verify` is green: 125 unit tests and 188 integration tests.
   - **Planted-bug check, all 9 caught:**
     - **Locking:** locks taken in descending order, system accounts locked, status checked only before the lock, the row lock removed.
     - **The timeout:** set for the session instead of the transaction, missing entirely, not translated.
     - **The funds check:** removed, or off by one.

     The three caught by unit tests were rerun with failures ignored, and the integration tests caught each of them too. With the Java funds check removed, the `CHECK` still stopped every overdraft, and the tests failed loudly on its untranslated error.
2. ✅ **Retries and the 503:**
   - **`RetryingTransactions`** (ledger module) replaces the plain `TransactionTemplate` in `TransferService` and `FundingService`:
     - It retries `40P01` and `40001`, up to 3 attempts, with full-jitter pauses of up to 25 ms, then up to 50 ms.
     - It retries only a transaction it started itself.
     - A lock timeout is never retried; it becomes `AccountBusyException`.
   - **The busy response:** 503 `/problems/account-busy` with `Retry-After: 1` (design §8).
   - **Tests:**
     - **`RetryingTransactionsTest`:** 11 unit tests with a fake transaction manager, so no database and no sleeping.
     - **Real forced deadlocks:**
       - `TransferServiceIT`: two account rows locked in opposite orders.
       - `FundingServiceIT`: an account row against a `LOCK TABLE fundings`.

       In both, the service had waited longer, so Postgres aborted it, and the retry succeeded. The log shows one retry for each.
     - **`TransfersApiIT`:** a 503 with `Retry-After`, then the same key succeeds.
   - **Tests:** `./mvnw verify` is green: 136 unit tests and 191 integration tests.
   - **Planted-bug check, all 8 caught:**
     - **Retry rules:** no retries, retrying inside a joined transaction, retrying lock timeouts, exhausted retries thrown raw.
     - **The response:** busy left unmapped, no `Retry-After`.
     - **Wiring:** funding not wired to the retry helper, transfer not wired.
3. ✅ **The concurrency suite (`ConcurrencyIT`):**
   - **1,000 mixed requests on 32 threads against four accounts:** 70% transfers of up to 500.00, 20% fundings, 10% repeated keys. The invariant checker runs throughout.
     - Every outcome must be one the API has an answer for.
     - Each balance must equal what the successes add up to.
     - Successes, business rows, and audit rows must match one-to-one.
     - Every check must be clean, during the load and after.
   - **No deadlocks without retries:** 400 postings naming three accounts in shuffled orders, straight through `PostingService`. All must succeed.
   - **Measured** (local machine, Testcontainers Postgres): the 1,000 requests took **3.6 to 4.5 seconds** in 7 runs, with about 50 invariant checks during each load, all clean, and no 503s.
   - **Stability:** 5 of 5 repeated runs green for `ConcurrencyIT`, `PostingLocksIT`, `TransferServiceIT`, `FundingServiceIT`, and `TransfersApiIT`.
   - **Planted-bug check, all 3 caught:**
     - **Locks taken in entry order:** caught only by the no-retry deadlock test. The 1,000-request test *passed*: retries and 503s absorbed the deadlocks, at a cost of 241 seconds and 291 503s. This is why the deadlock test bypasses the retry layer.
     - **No row lock:** overdraws reached the `CHECK`, an outcome the API has no answer for.
     - **A lost update** (no lock, with the balance computed in Java and written back): the three accounts' balances summed to 3,000,001 instead of 3,000,000.
   - **Tests:** `./mvnw verify` is green: 136 unit tests and 193 integration tests.
4. ✅ **Docs and close:**
   - **Docs:** design §3, §5.1, §7 (seven M5 rows, each pointing to a test), §8 (503), and §9. Also the glossary, primer 05 (new), primer 04's status table, README, `.env.example`, and the CLAUDE.md locking, error, and testing rules.
   - **Also corrected at the owner's request:** the trigger wording in ADR-0020 (marked as corrected), the glossary, and three test comments. Triggers catch mistakes by any role; the owner or a superuser can switch them off on purpose.
   - **Found while closing:** an IDE compiling into `target/` (Eclipse's compiler) rejected a pattern `switch` in `ConcurrencyIT` that `javac` accepts. Its stub class then failed the build with "Unresolved compilation problems". The code is now a plain `if`/`instanceof`, which both compilers accept.
   - **Tests:** `./mvnw clean verify` is green: 136 unit tests and 193 integration tests.

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
| 🔍 | **M5 Concurrency** | Ordered locking, `lock_timeout`, retries; 1,000-request and deadlock tests, with the invariant checker (built in M3a) run under concurrency. Implemented; awaiting CI and teach-back. |
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
| Negative-balance policy for forced reversals (default proposal: shortfall to a customer-receivables account) | M8 |
| Operator identity for cross-tenant admin actions (reversals, resolving NEEDS_REVIEW). Today an `admin` key acts only on its own client's accounts (ADR-0018). | M8 |
| Rate-limiting library | M15b (before any public deployment) |
| `max_attempts`, backoff, hold lifetime | M9b |
| Public deployment and budget | M16 |
| Run migrations as a separate deployment step, so the app process never holds owner credentials (ADR-0015) | M16 |
