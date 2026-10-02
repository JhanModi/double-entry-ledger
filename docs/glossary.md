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

## Platform

- **API client.** A business that uses the API (a tenant). It owns customer accounts and API keys.
- **Scope.** A permission on an API key: `read`, `write`, or `admin`.
- **Tenant isolation.** A client can never see or move another client's money.
- **FX quote.** A locked exchange rate with an expiry, single-use, bound to one client and currency pair.
- **Spread.** The difference between the market rate and the quoted rate. The platform's FX revenue, posted to a fee account.
