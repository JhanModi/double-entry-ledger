# Primer: Concurrency, locks, and retries

M5 learning notes: how two requests on the same account take turns, why they can't deadlock, what happens when one waits too long, and how to test all of it without guessing at timing.

---

## 1. The problem: checking a value that's already out of date

Primer 00 showed the classic race. Two withdrawals of 80 from a balance of 100 both read 100, both pass the funds check, and both subtract. Each check was right when it was made, but out of date by the time the money moved.

M4b was already safe from that particular race, for a subtle reason. A balance changes by a **delta** (`SET posted_balance = posted_balance + :delta`), and an `UPDATE` locks the row it changes, so the second request waits, then applies its delta to the committed balance. The `CHECK` constraint then rejects an overdraft.

But anything the code *read* earlier could still be out of date. A transfer read an account's status without a lock, wrote its entries, and only then updated the balance. If another transaction closed the account in between, the transfer still credited it. M5 has a test that fails on M4b's code for exactly this reason.

---

## 2. Row locks in Postgres

`SELECT … FOR <mode>` locks the rows it returns until the transaction ends. Other transactions asking for a conflicting lock on those rows **wait**. Plain `SELECT`s never wait: they read the last committed version.

| Mode | Taken by | Blocks |
|---|---|---|
| `FOR UPDATE` | `DELETE`, or an `UPDATE` of a key column | every other row lock |
| `FOR NO KEY UPDATE` | any other `UPDATE`, and the posting service | `FOR UPDATE`, `FOR NO KEY UPDATE`, `FOR SHARE` |
| `FOR SHARE` | rarely used here | the update modes |
| `FOR KEY SHARE` | **foreign-key checks**, when a row pointing at this one is inserted | only `FOR UPDATE` |

**Why the posting service uses `FOR NO KEY UPDATE`:** inserting an entry, a transfer, or a funding checks its foreign key to `accounts` by taking `FOR KEY SHARE` on the account's row. `FOR NO KEY UPDATE` doesn't block that; `FOR UPDATE` would. The same table shows why **system accounts are never locked**: a funding only takes `KEY SHARE` on the settlement account (through the entry's foreign key), and `KEY SHARE` locks don't block each other, so a thousand fundings never queue behind one another.

---

## 3. Lock, then check, then write

`PostingService.post` now runs in this order (ADR-0005):

1. **Limit lock waits** for the rest of the transaction (section 5).
2. **Read the accounts without a lock.** That's only safe for what can never change: an account's existence, kind, type, and currency. A trigger rejects any change to them.
3. **Lock the customer accounts**, in ascending id order, and re-read their **status and balances under the lock**. Anything that *can* change is only trusted once it's locked.
4. **Check** with `BalanceChanges.requirePostable`: every account open, and none would go below zero. It's pure Java, so every rule has a unit test.
5. **Write** the ledger transaction, the entries, and the balance deltas.

Nothing can change the locked values between step 3 and the commit, so the check can't go stale.

**Defense in depth:** the `CHECK (posted_balance - held_balance >= 0)` constraint is still there. But its error is no longer translated into "insufficient funds". If it ever fires, the Java check under the lock was wrong, which is a bug, so it surfaces as a 500 and gets logged. The planted-bug check removed the Java check to prove the backstop still holds: tests failed loudly with the constraint's error, and no balance went negative.

---

## 4. Deadlocks, and why the lock order matters

A **deadlock** is two transactions each waiting for a lock the other holds. Neither can move, so Postgres has to step in.

**How Postgres finds one:** a transaction that has waited for `deadlock_timeout` (1 second by default) runs a deadlock check. If it finds a cycle, it aborts *itself* with SQLSTATE `40P01`, which releases its locks so the other can go on. The transaction that started waiting first runs its check first, so it's the one aborted. M5's deadlock tests rely on that to choose which side loses.

**How we avoid them:** every posting locks its accounts in the same order, ascending id. Two postings can then never each hold what the other wants: whoever gets the lowest id first goes first, and the other waits without holding anything it needs.

```sql
SELECT id, status, posted_balance, held_balance
FROM accounts
WHERE id IN (:ids) AND kind = 'CUSTOMER'
ORDER BY id
FOR NO KEY UPDATE
```

Postgres sorts the rows (`ORDER BY`) *before* it locks them, so one statement takes the locks in id order. Ids never change, so the order can't shift while the statement waits. (Java has to agree with Postgres on what "ascending" means for a UUID. `AccountId.compareTo` compares unsigned bytes, as Postgres does; `LedgerSchemaIT` checks that.)

---

## 5. Lock timeouts

Without a limit, a request waits for a lock **forever**. One forgotten transaction holding an account (a `psql` session left open, say) would stall every request for that account. Each one holds one of the pool's 10 database connections while it waits, so ten of them stall the whole API.

`lock_timeout` is Postgres's limit on how long one statement may wait for a lock. When it runs out, the statement fails with SQLSTATE `55P03`. The posting service sets it at the start of every posting:

```sql
SELECT set_config('lock_timeout', '2000ms', true)
```

- The `true` makes it **local to the transaction**, like `SET LOCAL`, so it never carries over to the next request that uses the same pooled connection. Unlike `SET LOCAL`, `set_config` accepts a bind parameter.
- **Why 2 seconds:** it's longer than `deadlock_timeout` (1 second), so a deadlock is still caught as a deadlock and retried, rather than turning into a timeout first. A posting holds its locks for milliseconds, so a 2-second wait means something is genuinely stuck.
- The value comes from configuration (`LEDGER_LOCK_TIMEOUT`, default `2s`), and zero is refused at startup, because to Postgres zero means "no limit".
- A timeout becomes `AccountBusyException`, and the client gets **503**.

---

## 6. Retries

When Postgres aborts a transaction to break a deadlock, that transaction did nothing: the rollback undid all of it. Running it again is therefore safe, and the idempotency key still guards it, just as it guards a client's own retry. `RetryingTransactions` does this:

- **What it retries:** SQLSTATE `40P01` (deadlock) and `40001` (serialization failure). Errors are recognized by code, which Postgres documents and keeps stable, not by message text or by which Spring exception wraps them. (`40001` can't happen at our READ COMMITTED isolation level; it's listed for the day something runs at a stricter one.)
- **What it never retries:**
  - A **lock timeout**. The account is busy, and trying again straight away adds load exactly where it hurts. The client retries later instead.
  - **Business errors** and anything else. Retrying "insufficient funds" can't help.
  - **Work that joined someone else's transaction.** That transaction is already aborted, and only its owner can start again.
- **How often:** at most 3 attempts. Before each retry it pauses for a random time up to 25 ms, then up to 50 ms. The randomness ("jitter") stops two aborted transactions from retrying in lockstep and colliding again.
- **When it gives up:** `AccountBusyException`, and 503 for the client.

Retries can hide a bug: with them, a broken lock order would just look slow. That's why the deadlock test in `ConcurrencyIT` calls the posting service directly, *without* the retry layer, and requires every posting to succeed first time.

---

## 7. 503, and why not 409 or 429

| Status | Meaning here | Why not for "busy" |
|---|---|---|
| 409 | "Duplicate: this was already done" | A client that treats 409 as done would drop a transfer that never happened |
| 429 | "You're sending too much" | It's about the client's rate (M15b), not about one busy account |
| **503** | **"Temporarily unable; try again"** | Nothing moved, so retrying with the same `Idempotency-Key` is safe |

The response has a `Retry-After: 1` header: wait a second before retrying. Many HTTP clients already retry a 503 by themselves.

(Since M6, 409 has a second problem type, `request-in-progress`: another request with the same key hasn't finished. It's told apart from `duplicate-request` by its `type`; primer 06, section 7.)

---

## 8. Testing concurrency without guessing

`Thread.sleep(100)` in a test is a guess: it's flaky on a slow machine and slow on a fast one. M5's tests ask Postgres instead:

- **`HeldTransaction`** holds a transaction open on its own thread, as the owner, until the test ends it. It can be handed a next step with `then(…)`.
- **`DatabaseLocks.awaitASessionWaitingForALock`** polls `pg_locks` until some session is blocked, so the test knows the code under test is waiting.
- **`DatabaseLocks.isLocked`** asks for an account's lock with `NOWAIT`, which fails at once instead of waiting if someone else holds it. That's how `PostingLocksIT` proves a posting already holds the *lower* id while it waits for the higher one.
- **Forcing a real deadlock:** the test's transaction locks account B; a transfer locks A and waits for B; then the test asks for A. Each waits for the other. The transfer started waiting first, so Postgres aborts it. The test's request for A can only succeed once the transfer has rolled back, which proves the first attempt really was aborted, and then the retry succeeds.
- **The invariant checker under load:** `ConcurrencyIT` releases 1,000 requests at once and runs the checker over and over while they commit. The checker reads at REPEATABLE READ, so each run sees one consistent snapshot, and every run must be clean.
- **Testing each layer on its own:** the deadlock test runs without retries; the lock-timeout test runs against the posting service; the `CHECK` backstop has its own schema test.

---

## Further reading
- PostgreSQL, explicit locking (row-level lock modes and their conflicts): https://www.postgresql.org/docs/current/explicit-locking.html
- PostgreSQL, `lock_timeout`: https://www.postgresql.org/docs/current/runtime-config-client.html#GUC-LOCK-TIMEOUT
- PostgreSQL, `deadlock_timeout`: https://www.postgresql.org/docs/current/runtime-config-locks.html
- PostgreSQL, the locking clause of `SELECT` (sorting before locking): https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE
- PostgreSQL, error codes (SQLSTATE): https://www.postgresql.org/docs/current/errcodes-appendix.html
- AWS Architecture Blog, "Exponential Backoff And Jitter": https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/
- RFC 9110, 503 and `Retry-After`: https://www.rfc-editor.org/rfc/rfc9110#name-503-service-unavailable
