# Primer: Moving money safely

M4b learning notes: transactions, idempotency keys, audit trails, and how to test that they hold under concurrency.

---

## 1. Business records beside the ledger

A ledger transaction records *what* moved: debit this account, credit that one. It doesn't record *who* asked, in which request, or under which idempotency key. Those facts belong to the business operation, so each kind of movement gets its own table (`transfers`, `fundings`), and each row points to exactly one ledger transaction.

The ledger stays generic: it never learns about clients, keys, or requests. Payments (M9) and FX (M12) will add their own tables the same way.

---

## 2. One transaction, all or nothing

A **database transaction** groups statements so they're committed together or not at all. A transfer writes:
- a ledger transaction and its entries
- two balance changes
- a transfer row
- an audit row

If anything fails part way, such as insufficient funds or a duplicate key, the **rollback** undoes everything before it. No half-transfer can exist.

**In Spring:**
- **`@Transactional` works through a proxy.** Spring wraps the bean, starts a transaction when a method is called from outside, and commits or rolls back when it returns. A call from *inside* the same class skips the proxy, so its annotation does nothing. `ClientService.createClientWithKey` relies on that on purpose: the inner calls join its transaction.
- **Propagation** says what to do if a transaction is already open:
  - `REQUIRED` (the default) joins it, or starts one if there's none.
  - `MANDATORY` throws if there's none. `AuditLog.record` uses it, so an audit row can never be written separately from its action.
- **`TransactionTemplate`** runs a block in a transaction, in code rather than by annotation. `TransferService` uses it because one case needs code that runs *after* the rollback: looking up the request that won a race.

---

## 3. Composite foreign keys: tenant isolation in the database

An ordinary foreign key says "this account id exists". A **composite** one says "an account with *this id, this owner, and this currency* exists":

```sql
FOREIGN KEY (source_account_id, client_id, currency) REFERENCES accounts (id, client_id, currency)
```

So even if the Java ownership check had a bug, the database would refuse a transfer that touches another client's account. That isn't hypothetical: the planted-bug check removed the Java check, and the database still rejected the row and rolled the whole transfer back.

---

## 4. Idempotency keys and unique constraints

A client sends a transfer, the network drops the response, and the client retries. Without protection, the money moves twice. The client sends the same `Idempotency-Key` both times, and the database has `UNIQUE (client_id, idempotency_key)`.

**What each transaction can see** (READ COMMITTED, Postgres's default):
- A transaction sees only rows that other transactions have *committed*.
- Two identical requests can therefore both pass a "have I seen this key?" check, if neither has committed yet.

**Why the unique constraint still saves us:**
- When the second request inserts the same key, Postgres makes it **wait** for the first transaction to finish.
- If the first commits, the second's insert fails, the second rolls back entirely, and it answers 409.
- If the first rolls back, the second simply carries on.

The unique constraint is what guarantees "never twice".

**Why the key is checked first anyway:** a retry that arrives after the first request has *finished* should get "duplicate", even if the money has since been spent. Checking the key before anything else makes that the answer.

**The gap until M6:** if two identical requests overlap and the first spends all the money, the second may fail its balance check before it ever reaches the unique key, and get 422 instead of 409. The money still moves once. M6 fixes this by claiming the key as the very first write.

---

## 5. Testing races without sleeping

`Thread.sleep(100)` in a test is a guess. It's flaky on a slow machine and slow on a fast one. Instead:
- **Latches** (`CountDownLatch`) release many threads at the same moment, to create real overlap.
- **To force one exact interleaving,** hold the first transaction open, and ask Postgres when the second is blocked (`SELECT … FROM pg_locks WHERE NOT granted`). Only then let the first commit. `TransferServiceIT.aDuplicateThatLosesTheRaceIsStoppedByTheUniqueKeyAndNeverApplied` does this, so the unique-key path runs every time, not just when the timing happens to work out.

---

## 6. Request ids and the MDC

A web server serves requests on a **pool of threads**: one thread handles a request, then the next. The logger's **MDC** is a per-thread map whose values are added to every log line.

`RequestIdFilter` puts the request id in the MDC when a request starts, and removes it in a `finally` block when the request ends. Otherwise the next request on that thread would log under the wrong id.

---

## 7. Strict JSON

Parsing JSON into Java involves **coercion**: turning one kind of value into another. The library's lenient defaults turned `{"amount": 10.5}` into 10, so a transfer moved a different amount than the client sent. A failing test showed this before it was fixed. Now a whole-number field accepts only a JSON integer, and an unknown field is an error instead of being silently dropped.

---

## 8. Error status codes

| Status | Meaning here |
|---|---|
| 400 | The input is malformed: `invalid-request`, with `errors` naming each field at fault |
| 401 | No valid key |
| 403 | The key can't do this |
| 404 | The client has no such thing (and can't tell whether it exists for someone else) |
| 409 | This repeats something already done |
| 422 | Well-formed, but the business rules say no (insufficient funds, wrong currency, …) |
| 500 | A bug on our side, logged and never explained to the client |
| 503 | Try again later: other requests are using the same account (added in M5; primer 05) |

A rule of thumb: if a domain `IllegalArgumentException` reaches the API, validation missed something, so it's a 500, not a 400. That's why `@Positive` rejects a zero amount before any command is built.

---

## 9. Defense in depth, and testing each layer

Important rules are guarded twice:
- Scopes are checked by the security rules **and** by the service.
- Ownership is checked by the query **and** by a composite foreign key.

The catch: two layers can hide each other's bugs. When the funding rule was weakened in the planted-bug check, the service's check still answered 403, so a test that only checks "403 or not" can't tell which layer answered. The fix was a test whose answer *differs* by layer: with an empty body, a key that gets past the security rule hits validation first and gets 400 instead of 403.

---

## 10. Module boundaries

ArchUnit reads the compiled classes and checks who depends on whom:

```
web → transfers → ledger → clients → audit      (money: used by ledger, transfers, web)
```

A module may only depend on modules below it. The rule is tested against a planted violation, so a passing check means something.

---

## Further reading
- PostgreSQL, transaction isolation: https://www.postgresql.org/docs/current/transaction-iso.html
- PostgreSQL, explicit locking and `pg_locks`: https://www.postgresql.org/docs/current/explicit-locking.html
- Stripe, idempotent requests: https://docs.stripe.com/api/idempotent_requests
- RFC 9457 (Problem Details): https://www.rfc-editor.org/rfc/rfc9457
- ArchUnit user guide, layered architecture: https://www.archunit.org/userguide/html/000_Index.html#_architectures
