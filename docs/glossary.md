# Glossary

The terms used in code, docs, and the API. Use them consistently, and add to this file whenever a new term is introduced.

## Accounting

- **Account.** A named bucket of money in one currency. It's either a **customer account** (owned by an API client) or a **system account** (owned by the platform, e.g., bank settlement, FX pool, fees).
- **Customer account.** Owned by an API client. Has cached balances. Can't go negative.
- **System account.** Internal to the platform and never addressable through the public API. May go negative (e.g., bank settlement during reconciliation timing). Has no cached balance.
- **Debit / credit.** The two sides of an entry. Neither means "good" or "bad". Whether a debit increases or decreases a balance depends on the account's normal side.
- **Normal side.** The side that increases an account's balance. Assets and expenses are debit-normal. Liabilities, equity, and revenue are credit-normal.
- **Asset account.** Something the platform has, e.g., cash at the partner bank (the bank-settlement account).
- **Bank-settlement account.** The system account for the cash the platform holds at its bank, one per currency. An asset. Money from outside arrives through it: a funding debits it and credits a customer.
- **Account purpose.** The job of a system account that code needs to find, such as `BANK_SETTLEMENT`. Code finds such an account by purpose and currency, never by a hard-coded id, and there's at most one per purpose and currency.
- **Liability account.** Something the platform owes. Customer wallets are liabilities, because the platform owes that money to its customers.
- **Entry.** One line of a ledger transaction: an account, a direction, and a positive amount in minor units. (In accounting textbooks, "journal entry" often means the whole transaction. Here "entry" always means one line.)
- **Ledger transaction.** A group of entries that is recorded atomically and balances per currency. Immutable once written.
- **Posting.** Writing a ledger transaction and its entries.
- **Reversal.** A new ledger transaction that undoes an earlier one by swapping every entry's direction and referencing the original. History is never edited.
- **Double-entry.** Every movement is recorded on at least two accounts so debits equal credits, which means money is never created or destroyed inside the ledger.

## Balances

- **Minor units.** The smallest unit of a currency, stored as an integer: cents for USD, yen for JPY, fils for KWD (3 decimals).
- **Posted balance.** The balance from posted entries. Cached on customer account rows.
- **Held balance.** The total of ACTIVE holds on an account.
- **Available balance.** Posted minus held. What the customer can spend right now.
- **Derived balance.** A balance computed directly from entries. The cached balance must always equal it.
- **Invariant.** A rule that must always be true, e.g., "every ledger transaction balances". The **invariant checker** verifies these against the database.

## Money

- **Value object.** An object defined only by its values, never changed after creation. Two `Money` values with the same amount and currency are equal. `Money` is one.
- **Exponent.** The number of decimal places in a currency's minor unit (ISO 4217): USD 2, JPY 0, KWD 3.
- **Rounding mode.** The rule for rounding a value that falls between two whole minor units. **HALF_EVEN** ("banker's rounding") sends ties to the even neighbour (2.5 → 2, 3.5 → 4), so over many operations it doesn't drift up or down. **HALF_UP** sends ties away from zero (2.5 → 3, −2.5 → −3).
- **Allocation.** Splitting an amount into parts by ratios (fees, installments, splits) so the parts add up to exactly the original, with no minor unit lost or created.
- **Largest remainder method.** The allocation rule used here. Round every share down, then give the leftover minor units to the parts that lost the most in the rounding.
- **Property-based test.** A test that states a rule that must hold for *every* input (e.g., "parts add up to the original"). The library generates hundreds of inputs to try to break it.
- **Shrinking.** After a property-based test fails, the library reduces the failing input to the smallest one that still fails (e.g., "1 cent split 1:1"), which makes the bug easy to see.

## Payments

- **Transfer.** An instant movement between two customer accounts of the same client, inside the ledger. One database transaction. The `transfers` row records the business intent (who asked, in which request, with which idempotency key), and its ledger transaction records the money.
- **Funding.** Money arriving in a customer account from outside: the bank-settlement account is debited and the customer credited. Until M9 it's an API call with an `admin` key, standing in for a real inbound bank payment, and only for the caller's own accounts. Carries the bank's **external reference**, for reconciliation.
- **Amount limit.** The most one transfer or funding may move in a currency, set to roughly a million US dollars of real value (design §10). A sanity cap, not a risk limit.
- **Payment.** Money entering or leaving the system through the (mock) bank. It has a lifecycle and isn't instant.
- **Hold.** A reservation of funds for an outbound payment. It lowers the available balance without posting entries. Status: ACTIVE, CAPTURED, or RELEASED. Has an expiry time.
- **Authorization.** Placing a hold. The payment becomes AUTHORIZED.
- **Settlement.** The bank confirms the money moved. The hold is captured and entries are posted.
- **Bank instruction.** Our record of a request sent to the bank. Its id is the bank's idempotency key. Status: QUEUED, SENT, UNKNOWN, ACKED, REJECTED, or CANCELLED.
- **Sweeper.** A background job that retries stuck bank instructions and handles expired holds.
- **NEEDS_REVIEW.** A payment state meaning the system can't safely decide the outcome (e.g., retries were exhausted while the bank may have acted). An admin resolves it.

## Reliability

- **Idempotency key.** A client-chosen unique value sent with a write request, in the `Idempotency-Key` header. Repeating the request with the same key has the same effect as sending it once. Keys are unique per client. Until M6 a repeat gets 409 naming the original; from M6 it gets the original response.
- **Duplicate request.** A request whose idempotency key the client already used. Answered with 409 and the original's id, and never applied again.
- **Exactly-once effect.** A request may be *delivered* more than once, but its effect happens only once.
- **At-least-once delivery.** A message may arrive more than once but is never lost. Consumers must deduplicate.
- **Transactional outbox.** Events are written to a table in the same database transaction as the change they describe, then published by a relay.
- **Dual write.** Writing to two systems (e.g., database and broker) without a shared transaction. One can succeed while the other fails.
- **Reconciliation.** Comparing our ledger against an external record (the bank statement) and flagging differences. It never auto-corrects.
- **Defense in depth.** Guarding one rule in more than one layer, so a mistake in one layer isn't enough to break it. For example, the scope is checked by the security rules and again by the service, and ownership by the query and again by a composite foreign key. Each layer needs its own test, because one layer can hide that another is broken.
- **Race test.** A test that makes two operations overlap and checks the result is still correct. Here, threads start together on a latch, and a test that needs one exact interleaving holds a transaction open and waits until Postgres reports the other is blocked, rather than sleeping.
- **Lock, then check, then write.** How a posting stays correct under concurrency (ADR-0005): lock the accounts, re-read what can change (status, balances) under the lock, check it, and only then write. Nothing can change the locked values before the commit, so the check can't go stale.
- **Lock order.** The one order in which every posting locks accounts: ascending id. Two postings can then never each hold a lock the other is waiting for.
- **Deadlock.** Two transactions each waiting for a lock the other holds. Postgres notices after `deadlock_timeout` (1 second) and aborts one of them with SQLSTATE `40P01`.
- **Lock timeout.** The longest a statement may wait for a lock: Postgres's `lock_timeout`, set to 2 seconds for each posting's transaction (ADR-0022). When it runs out, the statement fails with SQLSTATE `55P03`, and the client gets 503 `account-busy`.
- **Transaction retry.** Running a whole transaction again after Postgres aborted it to break a deadlock. Safe because the aborted attempt left nothing behind. Done by `RetryingTransactions`: at most 3 attempts, and never for a lock timeout.
- **Jitter.** A random part of the pause before a retry, so two transactions that collided don't retry at the same moment and collide again.
- **Account busy.** The 503 answer when other requests held an account for longer than the lock timeout, or a transaction was aborted on every attempt. Nothing moved, and retrying with the same idempotency key is safe. Comes with `Retry-After: 1`.

## Database

- **Constraint.** A rule the database enforces on every write, e.g. `CHECK (amount > 0)`, whichever program does the writing.
- **Constraint trigger (deferred).** A trigger whose check waits until `COMMIT`. Used for "a transaction's debits equal its credits," which is only true once every entry is in.
- **Composite foreign key.** A foreign key over several columns, e.g. `(account_id, client_id, currency) → accounts (id, client_id, currency)`: the row must point to an account with *this* id, *this* owner, and *this* currency. Used so the database itself refuses a transfer that touches another client's account.
- **Unique constraint as a guard.** `UNIQUE (client_id, idempotency_key)` means two transactions can't both commit the same key. The second one waits for the first; if the first commits, the second fails and rolls back.
- **Append-only.** Rows can be inserted but never updated or deleted. Corrections are new rows. Enforced in two layers: triggers reject an update or delete by any role, which catches mistakes, and privileges stop the app. The triggers aren't a defense against the owner or a superuser, who can switch them off on purpose (ADR-0015).
- **Keyset pagination.** Paging by "rows after the last one I saw" (a cursor) instead of `OFFSET`. Costs the same on every page, and new rows don't shift earlier pages.
- **UUIDv7.** A UUID that starts with a timestamp, so new ids sort in creation order (ADR-0014).
- **REPEATABLE READ.** An isolation level where every query in a transaction sees the same snapshot of the database.
- **Row lock.** A lock on one row, held until the transaction ends. `SELECT … FOR NO KEY UPDATE` is the kind a posting takes on each customer account. It makes other writers of that row wait, but not the lighter `FOR KEY SHARE` lock that a foreign-key check takes, and never a plain `SELECT`.
- **SQLSTATE.** The five-character code Postgres puts on every error, such as `40P01` (deadlock) or `55P03` (lock not available). Code here recognizes database errors by it, not by the message's wording.
- **Role.** A Postgres user or group. A *login role* can connect; a *group role* (NOLOGIN) holds privileges that its members inherit.
- **Least privilege.** Giving each component exactly the access it needs and nothing more. The app's login can read and insert ledger rows and update three account columns (ADR-0015).
- **Owner.** The role that created a table. It can do anything to that table, including disabling its triggers, so it's trusted and used for migrations only.
- **Superuser.** A role that skips every privilege check. Never what the application connects as.
- **`session_replication_role = replica`.** A superuser-only setting that stops ordinary triggers firing, used for replication and restores. One reason triggers protect against mistakes rather than against superusers.

## Platform

- **API client.** A business that uses the API (a tenant). It owns customer accounts and API keys.
- **API key.** `dbl_<key id>_<secret>`. The *key id* is public and identifies the key. The *secret* is 256 random bits, of which only the SHA-256 hash is stored. Shown once, when it's issued.
- **Bearer token.** A credential that works for whoever holds it, sent as `Authorization: Bearer <token>`. API keys are bearer tokens, which is why they must never be logged.
- **Scope.** A permission on an API key: `read`, `write`, or `admin`. Each becomes a Spring Security authority (`SCOPE_read`, …).
- **401 vs 403.** 401 Unauthorized means "I don't know who you are" (no key, or an invalid one). 403 Forbidden means "I know who you are, and you can't do this" (a valid key without the needed scope).
- **Deny by default.** Every endpoint needs an explicit security rule; anything without one is refused.
- **IDOR (insecure direct object reference).** Reaching someone else's data by changing an id in a request. Prevented here by putting the owner in the SQL (ADR-0017).
- **Problem Details (RFC 9457).** The standard JSON error format (`type`, `title`, `status`, `detail`, `instance`), served as `application/problem+json`. Each kind of error has its own **problem type**, such as `/problems/insufficient-funds`, so clients can branch on it (catalogue in design §8).
- **400 vs 404 vs 409 vs 422 vs 503.** 400: the input is malformed (`invalid-request`, naming the fields). 404: the client has no such thing. 409: it repeats something already done. 422: well-formed, but the business rules refuse it. 503: try again later, because other requests are using the same account (`account-busy`).
- **Caller.** An API client making one request: its verified key plus the request's id and source address. Services that change something take a `Caller`, so they can check its scope and audit it.
- **Request id.** A UUID the server gives every request. It's in the `X-Request-Id` header, in every error, on every log line, and on audit rows, so one id ties a client's report to everything the request did. Never taken from the client.
- **MDC (Mapped Diagnostic Context).** A per-thread map the logger adds to every line. The request id is put there for the length of the request and removed afterwards, because the server reuses threads.
- **Audit log.** An append-only record of who did what, to what, when, and from where, written in the same transaction as the action. The app may only insert into it.
- **Actor.** Who performed an audited action: an API key (with its client, request, and address) or the operator at the command line.
- **Strict parsing.** Request JSON is read without guessing: an amount must be a JSON integer (never `10.5`, `"1050"`, or `1e3`), and an unknown field is an error rather than being ignored.
- **Tenant isolation.** A client can never see or move another client's money.
- **FX quote.** A locked exchange rate with an expiry, single-use, bound to one client and currency pair.
- **Spread.** The difference between the market rate and the quoted rate. The platform's FX revenue, posted to a fee account.
