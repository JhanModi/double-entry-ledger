# Glossary

The terms used in code, docs, and the API. Use them consistently, and add to this file whenever a new term is introduced.

## Accounting

- **Account.** A named bucket of money in one currency. It's either a **customer account** (owned by an API client) or a **system account** (owned by the platform, e.g., bank settlement, FX pool, fees).
- **Customer account.** Owned by an API client. Has cached balances. Can't go negative.
- **System account.** Internal to the platform and never addressable through the public API. May go negative (e.g., bank settlement during reconciliation timing). Has no cached balance.
- **Debit / credit.** The two sides of an entry. Neither means "good" or "bad". Whether a debit increases or decreases a balance depends on the account's normal side.
- **Normal side.** The side that increases an account's balance. Assets and expenses are debit-normal. Liabilities, equity, and revenue are credit-normal.
- **Asset account.** Something the platform has, e.g., cash at the partner bank (the bank-settlement account).
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

- **Transfer.** An instant movement between two customer accounts inside the ledger. One database transaction.
- **Payment.** Money entering or leaving the system through the (mock) bank. It has a lifecycle and isn't instant.
- **Hold.** A reservation of funds for an outbound payment. It lowers the available balance without posting entries. Status: ACTIVE, CAPTURED, or RELEASED. Has an expiry time.
- **Authorization.** Placing a hold. The payment becomes AUTHORIZED.
- **Settlement.** The bank confirms the money moved. The hold is captured and entries are posted.
- **Bank instruction.** Our record of a request sent to the bank. Its id is the bank's idempotency key. Status: QUEUED, SENT, UNKNOWN, ACKED, REJECTED, or CANCELLED.
- **Sweeper.** A background job that retries stuck bank instructions and handles expired holds.
- **NEEDS_REVIEW.** A payment state meaning the system can't safely decide the outcome (e.g., retries were exhausted while the bank may have acted). An admin resolves it.

## Reliability

- **Idempotency key.** A client-chosen unique value sent with a write request. Repeating the request with the same key has the same effect as sending it once.
- **Exactly-once effect.** A request may be *delivered* more than once, but its effect happens only once.
- **At-least-once delivery.** A message may arrive more than once but is never lost. Consumers must deduplicate.
- **Transactional outbox.** Events are written to a table in the same database transaction as the change they describe, then published by a relay.
- **Dual write.** Writing to two systems (e.g., database and broker) without a shared transaction. One can succeed while the other fails.
- **Reconciliation.** Comparing our ledger against an external record (the bank statement) and flagging differences. It never auto-corrects.

## Database

- **Constraint.** A rule the database enforces on every write, e.g. `CHECK (amount > 0)`, whichever program does the writing.
- **Constraint trigger (deferred).** A trigger whose check waits until `COMMIT`. Used for "a transaction's debits equal its credits," which is only true once every entry is in.
- **Append-only.** Rows can be inserted but never updated or deleted. Corrections are new rows.
- **Keyset pagination.** Paging by "rows after the last one I saw" (a cursor) instead of `OFFSET`. Costs the same on every page, and new rows don't shift earlier pages.
- **UUIDv7.** A UUID that starts with a timestamp, so new ids sort in creation order (ADR-0014).
- **REPEATABLE READ.** An isolation level where every query in a transaction sees the same snapshot of the database.

## Platform

- **API client.** A business that uses the API (a tenant). It owns customer accounts and API keys.
- **Scope.** A permission on an API key: `read`, `write`, or `admin`.
- **Tenant isolation.** A client can never see or move another client's money.
- **FX quote.** A locked exchange rate with an expiry, single-use, bound to one client and currency pair.
- **Spread.** The difference between the market rate and the quoted rate. The platform's FX revenue, posted to a fee account.
