# CLAUDE.md

Guidance for Claude Code in this repository. Read all of it before doing any work.

## Project context

- **What:** A fintech software engineering portfolio project. Its purpose is to show fintech employers what the owner can do as an engineer.
- **Audience:** Fintech engineers and hiring managers. They care about correctness when things fail (retries, concurrency, partial failures), auditability, security, tests, and clear reasoning. They care much less about how many features it has. A small system that is provably correct beats a large fragile one.
- **The owner's goal:** to understand every engineering decision and be able to defend it in an interview. Claude acts as a senior software architect and mentor, not a code generator.
- **Project name:** `double-entry-ledger`.
- **Product:** a double-entry ledger and payments API (backend only). API clients are businesses. They hold customer accounts, move money between them with instant transfers, and send or receive payments through a simulated bank. Every movement is a balanced, append-only posting, and balances can be proven correct.
- **Status (2026-10-02):** M4a (clients, API keys, accounts API) is done. M4b (transfers, funding, audit log) is in progress: its proposal is approved, and it's being built in the checkpoints listed in `docs/roadmap.md`. After M4b come M5, M6, and M7. No JPA: `JdbcClient` everywhere.

### Where things are
- `docs/roadmap.md`: milestones, their status, and decisions still open. **Check it at the start of every session.**
- `docs/adr/`: one ADR per architectural decision. Read the relevant ADRs before changing anything they cover.
- `docs/design.md`: architecture, flows, invariants, failure modes.
- `docs/glossary.md`: domain terms. Use them exactly as defined.
- `docs/learning/`: primers and teach-back questions for the owner.

### Stack (decided)
- **Language and build:** Java 25 (LTS), Spring Boot 4.x, Maven (with wrapper).
- **Data:** PostgreSQL 18, Flyway migrations, Spring `JdbcClient` with hand-written SQL.
- **Testing:** JUnit Jupiter, AssertJ, Testcontainers, jqwik, ArchUnit, k6.
- **Runtime and CI:** Docker Compose, GitHub Actions.
- **Events:** Kafka comes in M13, behind a transactional outbox.

**Pinned versions (M1):**
- Spring Boot 4.1.1 (it manages Testcontainers 2.x, Flyway, and the Postgres driver)
- Maven 3.9.16, via wrapper 3.3.4
- Spotless 3.10.3 with Palantir Java Format 2.101.0
- `postgres:18` in both Compose and tests
- gitleaks v8.30.1 in CI, pinned by image digest
- jqwik 1.10.1 and ArchUnit 1.5.1 (test only; versions in `pom.xml`)
- Spring Security and Bean Validation (starters managed by Spring Boot, added in M4a)

Maven versions live in `pom.xml`, and Dependabot proposes Maven and GitHub Actions updates. The gitleaks image in `ci.yml` is bumped by hand.

### Commands
- `./mvnw verify` (`.\mvnw verify` in PowerShell): compile, unit tests, integration tests, and format check. This is exactly what CI runs. **Docker must be running.**
- `./mvnw test`: unit tests only. Fast, no Docker needed.
- `./mvnw spotless:apply`: fix formatting.
- `./mvnw test "-Dtest=MoneyAllocation*"`: run only matching test classes (quoted so PowerShell passes it through intact).
  - **Gotcha:** `-Dtest` *replaces* Surefire's `*Test` pattern, so an exclusion like `"-Dtest=!Foo*"` also makes Surefire run the `*IT` classes.
- `docker compose up -d`, then `./mvnw spring-boot:run`: local database and app. Health check at `http://localhost:8080/actuator/health`.

### Still open (tracked in `docs/roadmap.md`; don't assume answers)
- License, and whether this file stays in the public repo (M7)
- Negative-balance policy for forced reversals (M8)
- Operator identity for cross-tenant admin actions (M8)
- Rate-limiting library (M15b). It must land before any public deployment (M16). Until then, the authentication and money-moving endpoints are knowingly not rate-limited.
- Retry and hold-expiry values (M9b)
- Deployment (M16)

## How Claude works with the owner

### The owner's permanent rules
These apply to every session and every milestone. Details are in the sections referenced.
1. **The owner does all Git and GitHub operations.** Claude may only run the read-only commands `git status`, `git diff`, `git log`, and `git show`. Claude never commits, pushes, or asks to commit. At the end of each milestone, Claude lists the changed files and suggests a commit message. See "Git conventions."
2. **Claude writes all the code.** No implementation exercises are offered.
3. **Proposal before code.** Every milestone starts with a proposal in the change-control format below. **No code is written for a milestone until the owner explicitly approves its proposal.** Approval covers only what was proposed.
4. **Every milestone ends with** 3–5 teach-back questions and a short **"Walk me through it"** section: the 2–3 most important pieces of code, explained in plain language the way the owner would explain them in an interview. The next milestone doesn't start until the owner has answered the teach-back.

### Working practices
- Work through the milestones in `docs/roadmap.md` in order.
  - Each milestone starts with a proposal in the change-control format below.
  - Each one ends with 3–5 teach-back questions. Don't start the next milestone until the owner has answered them.
  - Each one also ends with a short **"Walk me through it"** section: the 2–3 most important pieces of code, explained in plain language the way the owner would explain them in an interview.
  - Update the roadmap status and any affected docs as part of the milestone.
- **Claude writes all the code in every milestone** (the owner's permanent rule, 2026-10-02). Don't offer implementation exercises.
- For every meaningful decision, present the options, their tradeoffs, and a recommendation with reasoning. The owner decides.
- Briefly explain new concepts (e.g., idempotency keys, optimistic locking) the first time they come up.
- Prefer code the owner could have written and can explain over clever code.
- After each change, summarize what changed and why, and point to the key parts worth reading.
- If the owner asks a question, answer it. A question is not a request to change code.
- Be honest: say when you are unsure, report test failures as they are, and never claim something was verified when it was not.
- Flag risks, security concerns, and out-of-scope problems you notice. Mention them; do not silently fix them.

## Change control: no large changes without explanation

A change is **large** if it does any of the following:

- touches more than 3 files or roughly 150 changed lines
- adds a dependency, service, or piece of infrastructure
- changes the database schema, money logic, auth/security, or a public API contract
- changes module boundaries, directory structure, or the architecture
- deletes or rewrites code beyond what the task requires

Before a large change, stop, post a proposal in this format, and wait for an explicit yes:

> **Change:** what will change
> **Why:** the problem it solves
> **Options:** alternatives considered and their tradeoffs
> **Recommendation:** which option, and why
> **Risk:** what could break and how far the damage could reach
> **Verification:** how it will be tested

Also:

- Approval covers only the change described. Any extra scope needs its own approval.
- Work on one milestone at a time. Stop at the end of each milestone for review.
- Do not refactor unrelated code along the way.
- Never weaken, skip, or delete a test, or disable a lint, type, or security check, to make something pass.
- Never make a major architectural decision without explaining it first, even when it seems obvious.

## Architecture principles

These apply regardless of stack. The concrete architecture is in `docs/design.md` and the ADRs (a modular monolith, ADR-0001).

- Start with the simplest architecture that meets the requirements. Any distributed component (separate services, queues, caches) must be justified in an ADR, because each one adds new ways to fail.
- Domain logic (money math, state transitions, business rules) is pure code that does not depend on frameworks, the database, or the network. Side effects happen at the edges.
- Module boundaries are clear, and each piece of data is owned by exactly one module.
- The database is the source of truth. Multi-step state changes happen inside one DB transaction. Invariants are enforced with DB constraints (NOT NULL, CHECK, UNIQUE, FK) as well as in code.
- Lifecycles (payments, transfers, accounts) are modeled as explicit state machines, and illegal transitions are rejected.
- Every external call is designed to fail safely: it has a timeout, retries with backoff only when the operation is idempotent, and defined behavior on failure.
- If the system publishes events or messages, publishing must be reliable relative to the DB write (e.g., a transactional outbox). Never commit and then hope the publish succeeds.
- Configuration comes from the environment, not code.
- Logs are structured and every line carries a request/correlation ID. Logs never contain sensitive data.
- Each major decision is recorded as an ADR in `docs/adr/NNNN-short-title.md` with context, options, decision, and consequences.

## Financial calculations and data

- **Never use binary floating point for money.** That rules out `float`, `double`, a plain JS `number` for amounts, and SQL `FLOAT`/`REAL`. This project uses `long` minor units ([ADR-0002](docs/adr/0002-money-as-integer-minor-units.md)), with `BigDecimal` only inside FX math.
- Money is always an amount **and** a currency together, in a dedicated type. Never add, subtract, or compare different currencies without an explicit conversion.
- Currency precision follows ISO 4217. It is not always 2 decimals: JPY has 0 and KWD has 3.
- Round only at defined points, and name the rounding mode at each one. Never round intermediate values implicitly.
- Splitting an amount (fees, installments, allocations) must preserve the total exactly. Distribute any remainder deterministically.
- In JSON and other interchange formats, amounts are strings or integer minor units, never JSON floats.
- Validate amounts where they enter the system: positive where required, below an upper limit, in an allowed currency.
- Never trust client-supplied values for anything the server computes (fees, totals, exchange rates, balances).
- **Financial records are append-only.** Never UPDATE or DELETE a posted transaction or ledger entry. Correct mistakes with reversal or adjustment entries that reference the original.
- Every money movement balances (total debits = total credits per currency). Balances are a cached projection of the entries and are verified by the invariant checker ([ADR-0003](docs/adr/0003-entry-direction-and-positive-amount.md), [ADR-0004](docs/adr/0004-balances-as-cached-projection.md)).
- Operations that change balances must be safe under concurrent requests, using row locks or optimistic concurrency with versions. Never do an unprotected read-modify-write.
- Every endpoint that changes money state requires an idempotency key. The same key with the same request returns the same result and is applied once. The same key with a different request is an error.
- Store times in UTC with timezone information. Keep the event timestamp separate from the business/posting date. Never derive business dates from the server's local time.
- Every money movement can be traced: who started it, when, why, which request, and its external reference IDs (needed for reconciliation).
- Every calculation rule (fees, interest, FX, limits) has a written spec with worked examples, and tests that encode those examples.

### Database roles (ADR-0015)
- **The app connects as the restricted `ledger_service` login**, a member of the `ledger_app` group role. Flyway connects as the owner. Tests mirror this through `TestcontainersConfiguration`.
- **Every migration that adds a table must also `GRANT` its privileges to `ledger_app`**, and update the expected inventory in `AppRolePrivilegesIT`. Grant only what the app needs; prefer column-level `UPDATE`.
- **Describe the trust model accurately:**
  - Constraints and triggers catch *mistakes* by any role.
  - Privileges stop the *app*.
  - The owner is trusted (it can disable triggers), and a superuser can `SET session_replication_role = replica`.
  - Never claim triggers stop the owner.
- **Tests that must act as the owner use `OwnerDatabase`.** Never register a second `DataSource` bean in tests, or Spring Boot would give it to the app.

### API rules (ADR-0016, ADR-0017)
- **Every new endpoint needs an explicit rule in `SecurityConfiguration`** with the scope it requires. Anything without a rule is denied by design; never replace `denyAll()` with `authenticated()`.
- **Client-facing account lookups go through `LedgerQueries.accountOwnedBy(client, id)`.** Never load an account by id and check ownership afterwards. A non-owned account must give the same 404 as a missing one.
- **The owning client always comes from the authenticated principal**, never from the request body or parameters.
- **Errors are Problem Details** (`ApiExceptionHandler`), and stack traces and internal messages never reach a response.
- **Never log API keys or put them in exception messages.** Types that hold a key secret must hide it in `toString()`.
- **Beans that need the web server** (like the security filter chain) must be `@ConditionalOnWebApplication`, because the command-line mode runs without one. `ClientsCommandIT` runs in a non-web context to catch this.

### Money-movement rules (ADR-0018 to ADR-0021, M4b)
- **Business records sit beside the ledger.** A transfer or funding is a row in its own table that points to exactly one ledger transaction (`UNIQUE`). The ledger stays generic: it never learns about clients, keys, or requests.
- **New tables that reference a customer account use the composite key** `(account_id, client_id, currency) → accounts (id, client_id, currency)`. The database then rejects another client's account or a different currency, even if the Java check has a bug.
- **Transfers are same-client only.** Both accounts are looked up with `accountOwnedBy`.
- **`admin` acts only on the caller's own client's accounts** until the M8 operator-identity decision.
- **System accounts are found by `purpose`** (e.g. `BANK_SETTLEMENT`), never by a hard-coded id, and their ids never appear in a request or a response. A migration that adds a currency must also add that currency's settlement account (a test checks this).
- **Every money-moving POST requires an `Idempotency-Key` header,** and its business table has `UNIQUE (client_id, idempotency_key)`. Until M6 a duplicate gets 409, not a replay (ADR-0019).
- **Every state-changing action writes an audit row in the same transaction,** through `AuditLog` (ADR-0020). The app may only INSERT into `audit_log`.
- **Request ids are generated by the server,** never taken from the client (ADR-0021).
- **Request JSON is strict** (ADR-0021): amounts must be JSON integers, and unknown fields are a 400.

### Ledger and payment rules for this project
- **Writes:** only the `ledger` module writes entries and balances, and every posting goes through the posting service.
  - Balance changes are SQL deltas (`SET posted_balance = posted_balance + :delta`), never a read-modify-write in Java.
- **Locking** ([ADR-0005](docs/adr/0005-pessimistic-row-locking.md)): customer accounts are locked with `FOR NO KEY UPDATE`, in ascending id order.
  - Re-read balances after locking.
  - System accounts are never locked.
- **Transactions:** no network or other external calls inside a database transaction.
- **System accounts** are never addressable through the public API.
- **Payment status** changes only through conditional updates (`WHERE id = ? AND status = ?`, exactly one row affected), and every change is recorded in history.
- **Bank timeouts** ([ADR-0006](docs/adr/0006-holds-table.md), [ADR-0010](docs/adr/0010-in-process-mock-bank-and-recovery.md)):
  - A bank timeout never marks a payment FAILED.
  - An expired hold is auto-released only if its bank instruction is still QUEUED, and the instruction is cancelled in the same transaction. Otherwise the payment goes to NEEDS_REVIEW.
- **Idempotency keys** are claimed in the same transaction as the operation ([ADR-0007](docs/adr/0007-idempotency-in-the-business-transaction.md)).
- **Events** are written to the outbox in the same transaction and never published directly ([ADR-0009](docs/adr/0009-transactional-outbox.md)).

## Security requirements

- Treat all data as real, even when it is synthetic.
- No secrets in code or git. Load them from the environment or a secret manager. `.env` is gitignored, and `.env.example` holds dummy values.
- Never commit real personal or financial data. Seed data and fixtures are synthetic.
- Never write your own crypto or auth primitives; use well-maintained libraries. If the system has passwords, hash them with argon2id (or bcrypt).
- Authorization is deny-by-default and enforced on the server for every request. Every resource access checks ownership, which prevents IDOR (one user reaching another user's data by changing an ID).
- Validate all input against a schema where it enters the system. Use parameterized queries only.
- Collect only the data you need. Use TLS in transit and encrypt sensitive fields at rest. Mask identifiers in the UI and in logs (show the last 4 only).
- Never log secrets, tokens, passwords, full account or card numbers, or unmasked PII.
- Never store card numbers or CVVs. If cards are involved, use the provider's tokenization so the system stays out of PCI scope.
- Keep an append-only audit log of security-relevant and financial actions, recording actor, action, target, time, and origin.
- Rate-limit authentication endpoints and endpoints that move money. (Scheduled for M15b, and required before any public deployment. Until then this rule is knowingly unmet.)
- Webhooks: verify signatures, reject replays, and process them idempotently.
- Pin dependencies with a lockfile. CI scans for known vulnerabilities and leaked secrets. New dependencies need the owner's approval.
- Errors returned to clients never include stack traces or internal details.

## Testing requirements

- Every change ships with tests. A bug fix starts with a failing test that reproduces the bug.
- **Naming:**
  - `*Test` classes are unit tests. They need no Docker and are run by Surefire in `./mvnw test`.
  - `*IT` classes are integration tests. They are run by Failsafe in `./mvnw verify` and use Testcontainers through `@Import(TestcontainersConfiguration.class)`.
- **Integration tests share one database, and ledger data is append-only, so it can't be cleaned up:**
  - Each test creates its own accounts (see `LedgerFixtures`) and never asserts on global counts.
  - Raw SQL in tests may only *commit* balanced transactions on system accounts. Anything else must run rollback-only, or the global invariant checker will (rightly) fail.
  - A test that needs an empty ledger uses `@DirtiesContext(classMode = BEFORE_CLASS)` for a fresh container, plus `@Transactional` to roll each test back (see `InvariantCheckerIT`).
- Test layers:
  - **Unit:** domain and money logic. Fast, pure, and exhaustive.
  - **Integration:** against a real database, not mocks, so transactions, constraints, and migrations are actually exercised.
  - **API:** every endpoint, including error responses and authentication failures.
  - **End-to-end:** a few tests covering the core flow.
- Property-based tests check the money invariants: entries always balance, allocations preserve totals, and money is never created or destroyed.
- Every operation that changes a balance has concurrency tests.
- Idempotency tests confirm that retries and duplicate requests never apply twice.
- Authorization tests confirm that user A can never read or change user B's resources.
- Failure-mode tests cover external timeouts and errors, partial failures, and a crash between steps.
- Tests are deterministic: the clock and ID generators are injected, there are no real network calls, and there is no sleep-based timing.
- Coverage is a signal, not a goal. Money and auth code should have every branch covered.
- CI runs the full suite on every push, and `main` stays green.
- Before saying work is done, run the relevant tests and report the actual results, including any failures.

## Coding conventions

Tooling: Java 25 and the Maven wrapper (`./mvnw`), with Spotless running Palantir Java Format. CI enforces all of it.

- Base package: `io.github.jhanmodi.ledger`. Modules are subpackages, e.g. `io.github.jhanmodi.ledger.money`.

- Constructor injection only. No `@Autowired` on fields.
- Use Java records for immutable values and request/response DTOs.
- `@Transactional` goes on public service methods that are called from *another* bean. A call from inside the same class skips Spring's proxy, so no transaction is started.
- No JPA/Hibernate anywhere: all data access is `JdbcClient` with hand-written SQL ([ADR-0008](docs/adr/0008-jdbcclient-with-hand-written-sql.md), decided in M4).
- Inject `java.time.Clock` instead of calling `Instant.now()` directly, so tests can control time.

- Use the type system to make illegal states impossible to represent: a `Money` type, distinct ID types (e.g., `AccountId` vs. `UserId`), and enums for states.
- Use consistent domain vocabulary. Once terms are defined, keep `docs/glossary.md` up to date.
- Keep functions small and behavior explicit, with no hidden side effects or magic.
- Handle errors explicitly with typed domain errors. Never swallow an exception.
- Financial rules contain no magic numbers. Use named constants that include their units.
- Comments explain *why*, not *what*. No commented-out code. Every TODO says what is needed and why.
- Database migrations are versioned and forward-only. Never edit a migration that has already been applied.
- APIs are versioned and documented (OpenAPI if REST) and use one consistent error format.
- Prefer the standard library to adding a dependency.

## Git conventions

### Owner-only Git rule (permanent)
- **The owner does every Git and GitHub operation that changes the repo or talks to GitHub.** Claude never runs `git add`, `commit`, `push`, `pull`, `fetch`, `branch`, `merge`, `checkout`/`switch`, `reset`, `stash`, `tag`, or `remote`, or any GitHub CLI (`gh`) command. Claude never asks or offers to commit.
- **Allowed read-only commands:** `git status`, `git diff`, `git log`, `git show`.
- Creating or editing files is fine, including GitHub Actions workflows and Dependabot config.
- **At the end of each milestone,** Claude lists the files it changed and suggests a commit message (plus a PR description if useful). The owner does the rest.

### Conventions for the owner's commits
- The repository lives on GitHub (branch `main`). The owner manages the remote.
- `main` is always green and deployable. Branches are short-lived and prefixed `feat/`, `fix/`, `test/`, `refactor/`, `docs/`, or `chore/`.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): imperative summary` (72 characters or fewer), with a body that explains *why*.
- Commits are small and atomic: one logical change, with its tests in the same commit.
- Each milestone (or smaller unit) goes through its own PR, with a description of what changed, why, and how it was tested.
- Never commit secrets, `.env` files, real data, or build artifacts.
- Never force-push to `main`, and never skip hooks.

## Keeping this file current

When an open decision is made, update this file and add an ADR in the same change, before writing any code that depends on that decision.
