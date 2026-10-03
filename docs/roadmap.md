# Roadmap

Each milestone opens with a short proposal (the change-control format in `CLAUDE.md`). It closes when `mvn verify` passes locally and in CI, the invariant checker passes, and the owner has completed the teach-back questions.

Status: ✅ done · 🚧 in progress · 🔍 in review · ⏳ not started

## Current work: M6 Idempotency (approved 2026-10-03; implemented, in review)

**Starting point:** M4b's interim scheme (ADR-0019) already guaranteed that money never moves twice, through `UNIQUE (client_id, idempotency_key)` on the transfer and funding rows. What it lacked:
1. **A replay.** A retry got 409 with the original's id, not the original response, and fundings have no `GET`.
2. **Mismatch detection.** The same key with a different body also got 409.
3. **A clean answer to simultaneous duplicates.** The second could get 422 insufficient funds, because the balance was checked before the key.
4. **An honest answer to a long wait.** A same-key request waiting more than 2 seconds got 503 `account-busy`.

The proposal was approved on 2026-10-03 with these decisions (ADR-0023):
- **D1-A, only successes are remembered:** a failed request rolls back with its claim, so the key stays free and a retry runs again. No savepoints.
- **D2-A, a replay is rebuilt from the business row:** same status, body, and `Location`, plus `Idempotent-Replayed: true`. No stored responses, and no UPDATE on claims.
- **D3-A, the fingerprint:** SHA-256 over a versioned, length-prefixed encoding of the validated command, pinned by a test with independently computed values.
- **D4-A, a duplicate still running after the lock timeout:** 409 `request-in-progress` with `Retry-After: 1`. The claim sets the lock timeout before it inserts.
- **D5-A, expiry:** 24 hours by default, honoured until deleted; a scheduled job deletes expired claims in batches; the app gets DELETE on claims only.

### Left to close M6
1. ⏳ **Commit** (the owner).
2. ⏳ **CI green:** waiting for the owner to confirm (Claude can't see GitHub).
3. ⏳ **Teach-back** answered.

### Checkpoints (`./mvnw verify` passes after each, plus a planted-bug check)
Each planted-bug check ran in a scratchpad copy of the project, never in the working tree. A bug counts as caught only if a test fails; each failure was checked to be in the test written for it.

1. ✅ **The claim and the fingerprint:**
   - **V6** creates `idempotency_keys`:
     - **Columns:** client, key, operation, SHA-256 hash, creation and expiry times; `PRIMARY KEY (client_id, idempotency_key)`.
     - **CHECKs:** key format, known operations, a 32-byte hash, expiry after creation.
     - **A trigger** rejects any UPDATE. That trigger wasn't in the proposal; it's the same "catch mistakes by any role" guard the other tables have.
     - **Grants:** SELECT, INSERT, and DELETE for the app, no UPDATE.
   - **The `idempotency` module:**
     - `IdempotencyKey` moved here from `transfers`. `IdempotentOperation`, `RequestFingerprint`, `Claim`, the two new exceptions, and `IdempotencyKeys.claim` are new.
     - The claim runs `MANDATORY` (only inside a caller's transaction). It sets the lock timeout, then inserts with `ON CONFLICT DO NOTHING`, then reads the existing claim in a second statement.
     - `ledger.SqlState` became public, so the claim recognizes a lock timeout by its SQLSTATE.
     - ArchUnit layers: web → transfers → idempotency → ledger → clients → audit.
   - **`TransferCommand.fingerprint()` and `FundingCommand.fingerprint()`:** every field but the key, in a fixed order.
   - **ADR-0023** written. ADR-0019 is marked superseded, and ADR-0007 partly superseded.
   - **Tests:**
     - **Unit:** `RequestFingerprintTest` (8) and `MoneyMovementFingerprintsTest` (6). Both pin the encoding and two hashes, computed with `sha256sum`.
     - **Property:** `MoneyMovementFingerprintsPropertiesTest` (2).
     - **Integration:** `IdempotencyKeysIT` (11, including the held-claim races and the lock timeout), `IdempotencyKeysSchemaIT` (8), and `AppRolePrivilegesIT` (inventory updated, plus one test).
   - **Tests:** `./mvnw clean verify` is green: 152 unit tests and 213 integration tests.
   - **Planted-bug check, all 13 caught:**
     - **The fingerprint:** no length prefix, missing written as empty, the transfer's description left out, the funding's reference left out.
     - **The claim:** the operation not compared, no lock timeout before the claim, a claim allowed outside a transaction, the lookup ignoring the client, the retention ignored.
     - **The schema:** no never-change trigger, no operation CHECK, the app granted UPDATE, the app denied DELETE.
2. ✅ **Claim-first transfers and fundings:**
   - **`TransferService` and `FundingService`** claim the key first. A REPEAT replays the row found by `(client_id, key)`. After a NEW claim, they look for a row with the key before posting: one exists only if its claim expired, and the answer is `DuplicateRequestException` (409). They return `IdempotentResult`, which says whether the result was replayed.
   - **Moved forward from checkpoint 3:** the two new problem types (422 `idempotency-key-reused`, 409 `request-in-progress` with `Retry-After: 1`). Without them, the new exceptions would have reached the API as 500s in this checkpoint's commit.
   - **Tests first:** the new service tests ran against a signature-only version of the services. 18 of 57 failed, for example:
     - a retry got `DuplicateRequestException` instead of a replay;
     - a reused key wasn't detected;
     - a waiting duplicate got `AccountBusyException` (ADR-0022's gap).
   - **Tests:**
     - **`TransferServiceIT`:** rewritten for replays, reuse, a failed request leaving the key free, a deleted claim, another client's key, and three held-transaction races (commit, rollback, past the lock timeout).
     - **`FundingServiceIT`:** replays, reuse, and a transfer's key refused for a funding.
     - **`UniqueKeyBackstopIT` (new, its own Spring context):** with the claim layer mocked out, the unique keys still stop racing duplicates.
     - **`ConcurrencyIT`:** repeats must replay exactly their original's movement, and claims, rows, and audit rows must match.
   - **Tests:** `./mvnw clean verify` is green: 152 unit tests and 225 integration tests.
   - **Measured** (local machine, Testcontainers Postgres, nothing else running): `ConcurrencyIT`'s 1,000 requests took **3.87 to 4.08 seconds** in 4 runs, including the final one (M5: 3.6 to 4.5). Each run had 77 to 99 replays, and the invariant checker was clean throughout. So the extra INSERT per request costs nothing measurable at this scale. (Two runs made while planted-bug checks shared the machine took 7.6 and 9.1 seconds; they aren't counted.)
   - **Planted-bug check, all 11 caught:**
     - **Order:** the claim after the posting (in each service), no claim at all, no key check after a new claim (in each service).
     - **Replay:** reported as new, falling through to run again, the funding claimed as a transfer, the replay lookup ignoring the client.
     - **Errors:** "in progress" turned into "busy" by the retry layer, the backstop's unique violation not translated.
3. ✅ **The API:**
   - **The replay header:** a replay carries `Idempotent-Replayed: true` and the original's `Location`, through `IdempotencyKeyHeader.markIfReplayed`. Controller Javadoc updated.
   - **Tests** (7 new):
     - **`TransfersApiIT`:** a replayed response identical to the original, header and `Location` included; a re-serialized body with other spacing and field order still replays; 422 for a different body; 409 `request-in-progress` with `Retry-After` against a held claim, then a successful retry; 409 `duplicate-request` after the claim is deleted; Bob sending Alice's exact request with her key gets a 404 that never names her transfer, and with his own accounts the key is simply his.
     - **`FundingsApiIT`:** the replay header, 422 for a different funding, and a transfer's key refused for a funding.
   - **Not tests-first:** this checkpoint's tests were written after the controller change, so the planted-bug check is what shows they can fail.
   - **Tests:** `./mvnw clean verify` is green: 152 unit tests and 232 integration tests.
   - **Planted-bug check, all 8 caught:** the replay header missing, the header always set, `Location` lost on a replay, "in progress" without `Retry-After`, unmapped (500), or as 503, a reused key as 409, or unmapped.
4. ✅ **Cleanup:**
   - **`IdempotencyCleanup`:**
     - **What it deletes:** claims whose `expires_at` has passed (database time), oldest first, 1,000 per statement. Each batch commits on its own.
     - **When:** `@Scheduled` with a fixed delay, first one interval after startup (`LEDGER_IDEMPOTENCY_CLEANUP_INTERVAL`, default 10 minutes; zero is refused at startup).
     - **Where:** `@ConditionalOnWebApplication`, so the command-line mode never runs it. `SchedulingConfiguration` turns scheduling on.
   - **Tests:**
     - **`IdempotencyCleanupIT` (3):** as the app's restricted login, five expired claims go in batches of two and live ones stay; the web server schedules the task with the configured delays; a transfer whose claim was aged and then deleted by the real cleanup is refused with 409, and nothing moves.
     - **`ClientsCommandIT` (1):** the command-line mode has no cleanup bean.
   - **Tests:** `./mvnw clean verify` is green: 152 unit tests and 236 integration tests.
   - **Planted-bug check, all 5 caught:** live claims deleted too, only one batch per run, scheduling not enabled, the first run at startup instead of after one interval, the job present in the command-line mode.
5. ✅ **Docs and close:**
   - **Docs:**
     - design §3–§9: modules, the claim table, the transfer flow, the invariant, nine failure-mode rows, the error catalogue, idempotent retries, and the tests.
     - The glossary (eleven new or changed terms), primer 06 (new), the 409 notes in primers 04 and 05, the README, `.env.example`, and the CLAUDE.md status, error, idempotency, and testing rules.
   - **Tests:** `./mvnw clean verify` is green: 152 unit tests and 236 integration tests (M5 closed at 136 and 193).
   - **Planted bugs across M6:** 37, all caught.

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
- **Nothing checks that a transfer or funding row's amount matches its ledger entries.** Both are written together by the same code, but the invariant checker doesn't compare them yet (ADR-0018).
- **Nothing checks that every claim has its transfer or funding row.** They're committed together, and `ConcurrencyIT` counts them one to one, but the invariant checker doesn't compare them.
- **One branch of the claim is reviewed, not tested:** if the cleanup deletes an expired claim between the claim's insert and its read, the claim is tried again. That window can't be forced without a test hook in the code.
- **The 1,000-request test accepts 409 `request-in-progress`,** as it accepts 503s: on a slow machine a repeat may wait for its original longer than the lock timeout. None appeared in the measured runs.
- **The cleanup has no metric or alert.** A cleanup that stopped running would only show as a growing table. Metrics arrive in M14.
- **Local database:** V5 is applied locally, so it must never be edited. V6 will be applied the next time the app runs locally; once committed, it must never be edited either. New schema changes go in V7.
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
| 🔍 | **M6 Idempotency** | Claim/replay in the same transaction, request hashing, expiry cleanup; same-key concurrency tests. Implemented; awaiting commit, CI, and teach-back. |
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
