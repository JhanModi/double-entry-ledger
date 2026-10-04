# Milestone log

How each milestone was closed: the tests at the end, the planted-bug checks, measured numbers, and the teach-back. The [roadmap](roadmap.md) says what's done and what's next; this file keeps the working notes behind it.

**How a milestone runs** (the full process is in `CLAUDE.md`):
1. A written proposal: what changes, the options for each decision with their tradeoffs, a recommendation, the risks, and how it will be verified. No code until it's approved.
2. Checkpoints, each ending with `./mvnw verify` green and a **planted-bug check**: bugs are deliberately put into a scratchpad copy of the code, and each must make a test fail. A bug counts as caught only if a test fails, and each failure is checked to be in the test meant to catch it.
3. Docs updated in the same change, then CI green on the commit.
4. A teach-back: 3–5 questions on the milestone's decisions, answered before the next milestone starts.

Until M6, each milestone's detailed checkpoint record lived in `docs/roadmap.md` and was replaced when the milestone closed. The full records are still in git history at the commits listed below (`git show <commit>:docs/roadmap.md`).

| Milestone | Closed | Commit | Tests at close (unit / integration) | Planted bugs | Measured | Full record |
|---|---|---|---|---|---|---|
| M4a Clients, API keys, accounts API | 2026-10-02 | `a2d7803` | 73 / 90 | 3, all caught | | `9caf334` |
| M4b Transfers, funding, audit log | 2026-10-02 | `f7a8ce3` to `2700cde` | 111 / 182 | 23, all caught | | `2700cde` |
| M5 Concurrency | 2026-10-03 | `47b1c5f` | 136 / 193 | 20, all caught | 1,000 concurrent requests in 3.6–4.5 s, invariant checker clean | `47b1c5f` |
| M6 Idempotency | 2026-10-03 | `cd11a2e` | 152 / 236 | 37, all caught | 1,000 requests in 3.87–4.08 s, 77–99 replays per run | `cd11a2e` |
| M7 Resume checkpoint | in review | | 152 / 242 | 15 spec mismatches, all caught | 1,000 requests in 4.22–4.54 s | this file |

## M7 Resume checkpoint

**Implemented 2026-10-04; in review.** It closes when CI is green on its commit and the teach-back is answered.

- **Decisions, approved as recommended:**
  - D1-B: a hand-written `docs/openapi.yaml`, checked against the code by `OpenApiSpecIT` and linted in CI by Redocly (ADR-0024).
  - D2: diagrams in Mermaid. D3: a self-checking demo script. D6: a new `docs/architecture.md`.
  - D4: MIT. D5-A: everything published, history included (ADR-0025). Also a `SECURITY.md`.
- **Added at the owner's request:** this log, split from the roadmap; a CI badge on the README; and a captured demo transcript.
- **Beyond the proposal, all small:**
  - `redocly.yaml` sets the `recommended-strict` ruleset, with the localhost rule off. The CI job runs with `--network none` and telemetry off.
  - Glossary terms: OpenAPI spec, spec drift, planted-bug check.
  - A pointer in design §2 to the new diagrams.

### Checkpoints
1. **API spec:**
   - `OpenApiSpecIT` (6 tests) passed on its first run, so the planted-mismatch check is what shows it can fail.
   - **15 mismatches planted, all caught,** each by the test written for it:
     - 10 in the spec: an endpoint missing, an extra endpoint, a field renamed, a broken `$ref`, the amount typed as a string, `Idempotency-Key` made optional, a currency missing from the enum, a wrong `maxLength`, unknown fields allowed, and the wrong response body.
     - 5 in the code: a field renamed, a new record undocumented, the page-size limit changed, a new endpoint undocumented, and a field made required.
   - **Redocly:** the spec is valid under `recommended-strict`. A planted warning (an operation without an `operationId`) failed the lint with exit code 1.
2. **Architecture doc:** all four Mermaid diagrams (three in `docs/architecture.md`, one in the README) rendered with Mermaid 11 in a browser. That they render on GitHub is to be confirmed after the push.
3. **Demo:**
   - First, the Windows-specific parts were checked on their own against the running app: CRLF line endings in the CLI's output, the API key sent from a file, and curl's parallel config with Windows paths.
   - Then the full script ran twice in a row, against the app built from the M7 tree. Every check passed both times.
   - No API key appeared in either run's output or in the app's log. The second run is `docs/demo-transcript.md`.
4. **Publishing:**
   - **gitleaks** (CI's pinned v8.30.1 image), over all refs: 16 commits, no leaks. Over the working tree, without `.env` and `target/`: no leaks.
   - **By hand:**
     - Every commit's author and committer email is the GitHub no-reply address.
     - The only env file ever committed is `.env.example`, which holds placeholders.
     - No commit and no file in the working tree has a local path or personal details.
     - No API-key-shaped string appears anywhere in the history.
   - **Every relative link** in the changed docs resolves: 114 checked, anchors included.

- **Found along the way:** Spring's own errors other than 400, such as a 415, have no problem type. It's recorded as a known gap in the roadmap, and the spec says so.
- **Tests:** `./mvnw clean verify` is green: 152 unit tests and 242 integration tests (6 new).
- **Measured** (local machine, Testcontainers Postgres, nothing else running): `ConcurrencyIT`'s 1,000 requests took **4.22 to 4.54 seconds** in 4 runs: 3 on their own, and 1 inside the full build. Each run had 78 to 96 replays and 47 to 51 invariant checks during the load, all clean, and no 503s. In M6 it was 3.87 to 4.08 seconds. No production code changed in M7, so the difference comes from the machine, not the code.
- **Local database:** V6 is now applied locally too; the app ran for the demo.
- **Teach-back:** ⏳ not answered yet.

## M6 Idempotency

**M6 closed on 2026-10-03:**
- Committed as `cd11a2e`, and CI is green on it. The checkpoint record (decisions D1-A to D5-A, tests first, planted-bug checks, measured numbers) is in `docs/roadmap.md` as of `cd11a2e`. In short:
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
- **Local database (2026-10-03):** V5 is applied locally, and V6 is committed (it's applied the next time the app runs locally). Neither may ever be edited; new schema changes go in V7.

## M5 Concurrency

**M5 closed on 2026-10-03:**
- Committed as `47b1c5f`, and CI is green on it. The checkpoint record (decisions D1-A to D5-A, tests first, planted-bug checks, measured numbers) is in `docs/roadmap.md` as of `47b1c5f`. In short:
  - Postings lock, then check, then write, with a 2-second lock timeout. Deadlocks are retried, and a busy account is a 503.
  - `ConcurrencyIT` ran 1,000 requests in 3.6 to 4.5 seconds, with the invariant checker clean throughout.
  - 20 planted bugs, all caught.
- **Approved after the fact:** an untranslated `CHECK` violation is a 500, because it now means the check under the lock is buggy. ADR-0020 carries a "Corrected" line for the trigger wording.
- **Teach-back:** all five answers were correct. Refinements given:
  - Q1: in the test, the transaction that closed the account was the one holding the lock. The transfer's balance `UPDATE` waited for exactly that close, then applied its delta, because a delta doesn't re-check status. Existence is also safe to read without a lock: accounts are never deleted.
  - Q3: with a 500 ms timeout, Postgres would never run its deadlock check, so the "deadlock detected" log line would never appear either.
  - Q4: in Postgres, an aborted transaction can't "appear to succeed": every later statement fails (`25P02`) until it rolls back. The silent-loss risk exists only with savepoints, or in databases that roll back just the failed statement.
  - Q5: the 500 is logged in full with its request id today; alerting on it arrives with metrics in M14.
- **A planted bug worth remembering:** with locks taken in entry order instead of id order, the 1,000-request test still *passed*, because retries and 503s absorbed the deadlocks (at a cost of 241 seconds and 291 503s). Only the no-retry deadlock test caught it. That's why that test calls the posting service without the retry layer.

## M4b Transfers, funding, audit log

Five checkpoints, `f7a8ce3` to `2700cde`; the full record is in `docs/roadmap.md` as of `2700cde`. Highlights:
- **Strict JSON started with a failing test.** Before the fix, `{"amount": 10.5}` returned 201 and moved 10 minor units, and an unknown field was accepted.
- **Defense in depth, seen in a planted bug:** with the funding rule weakened from `admin` to `write`, the service's own scope check still answered 403, so `FundingsApiIT` passed. `ApiSecurityIT` caught it.
- **Without the destination ownership check,** the composite foreign key still refused the row and rolled the whole transfer back.

## M4a Clients, API keys, accounts API

Committed as `a2d7803`; the record is in `docs/roadmap.md` as of `9caf334`. Planted-bug checks: ignoring revocation, ownership SQL that leaked other clients' accounts, and `authenticated()` in place of `denyAll()`. Each was caught by its own test.

## M0 to M3b

These milestones predate the checkpoint records. Their scope is in the [roadmap](roadmap.md), and their decisions are in ADR-0001 to ADR-0015.
