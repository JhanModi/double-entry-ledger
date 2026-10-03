# Primer: Idempotency and replays

M6 learning notes: how a retried request gets the original result instead of moving money again, why the key is claimed inside the same transaction, how two requests with the same key at the same moment are kept apart, and how all of it is tested.

---

## 1. The problem: a retry can't tell what happened

A client sends a transfer, and the network times out. Did the transfer happen? The client can't know: the request may never have arrived, or it may have committed and only the response was lost. The safe thing for the client is to **retry**. The server's job is to make that retry harmless.

**Idempotent** means "doing it twice has the same effect as doing it once". A `GET` is naturally idempotent. A `POST /v1/transfers` isn't: each one moves money. The `Idempotency-Key` header makes it idempotent. The client picks a key for each *intended* transfer and sends the same key with every retry of it. The server promises:
1. **Applied at most once.** However many times the key arrives, the money moves once.
2. **The same answer.** A retry gets the original's result, so the client learns what happened.
3. **Misuse is caught.** The same key with a *different* request is an error, because it's almost certainly a client bug.

M4b already kept promise 1 with `UNIQUE (client_id, idempotency_key)` on the transfer row. A retry got 409 and the original's id, so promises 2 and 3 were missing. M6 adds them (ADR-0023).

---

## 2. Claiming the key, in the same transaction

The first statement of every money-moving transaction claims the key:

```sql
INSERT INTO idempotency_keys (client_id, idempotency_key, operation, request_hash, expires_at)
VALUES (:clientId, :key, :operation, :requestHash, now() + :retentionMillis * interval '1 millisecond')
ON CONFLICT (client_id, idempotency_key) DO NOTHING
```

`ON CONFLICT … DO NOTHING` means: insert the row, unless one with the same primary key already exists; then insert nothing and report 0 rows. There are three cases:

| Another row with this key… | What the insert does | Result |
|---|---|---|
| doesn't exist | inserts the claim | **NEW**: carry the request out |
| exists, committed | nothing | read it: same request → **REPEAT** (replay); different → **422** |
| exists, **not yet committed** | **waits** to see how the other transaction ends | commits → as above; rolls back → inserts the claim |

**Why the same transaction as the money movement?** Because then the claim and the transfer are committed together or not at all. A response cache in a servlet filter, or in Redis, is written *after* the business transaction commits. A crash between the two leaves the money moved but no record of the key, and the retry moves it again. That's a **dual write** (primer 00), and ADR-0007 ruled it out.

**Why read the existing claim in a second statement?** At READ COMMITTED, each statement sees the database as it was when *that statement* started. If the insert waited for another transaction that then committed, a select inside the same statement still couldn't see that row. The next statement can.

---

## 3. The fingerprint: "is this the same request?"

To tell a retry from a different request, the claim stores a **fingerprint**: the SHA-256 hash of the request.

- **A hash** turns any input into a fixed-size value (32 bytes). The same input always gives the same value. Two different inputs practically never give the same one. So storing the hash is enough to compare requests, without keeping a copy of them.
- **It's taken from the validated command, not the raw bytes.** `{"amount": 300}` and `{ "amount" : 300 }` are the same request. A hash of the raw body would call them different, and an honest retry from a client that re-serialized its JSON would get a 422.
- **The encoding is unambiguous.** Each item is written as its length in UTF-8 bytes, a colon, then the item: `3:USD`. Without lengths, the fields `"ab", "c"` and `"a", "bc"` would both become `abc`. A missing description is written as `-`, which differs from an empty one (`0:`).
- **It's a contract.** A retry that arrives just after a deploy is compared with a fingerprint the old code stored. If the encoding changed, the retry would get a 422. So the encoding carries a version tag (`v1`), and tests pin the exact text and hash. The expected values were computed with `sha256sum`, outside Java, so the tests don't just compare the code with itself.

```
2:v1  8:TRANSFER  15:sourceAccountId  36:0190f0a8-…  20:destinationAccountId  36:…  6:amount  4:1050  8:currency  3:USD  11:description  9:rent 😀
```

(Shown with spaces for reading; the real text has none.)

---

## 4. What a key remembers: only successes (D1)

If a transfer fails with "insufficient funds", everything rolls back, **including its claim**. The key is free again, and a retry runs the transfer again, which may now succeed.

The alternative was to store failures too, as Stripe does, so that a retry of a failed request gets the same 422. That needs a **savepoint**: a bookmark inside a transaction that you can roll back to without undoing everything. Claim, set a savepoint, try the transfer; on failure, roll back to the savepoint, which keeps the claim, and store the error. M6 doesn't do that, because:
- A failed request here has **no effect**. Nothing external happens inside the transaction, so there's nothing for a stored failure to protect.
- Savepoints are where a failure can be silently swallowed (M5 teach-back Q4).
- Some 422s, like "amount too large", are refused before any claim exists, so failures could never all be stored anyway.

The cost: a changed body sent after a failure isn't detected, because the failed request never took the key.

---

## 5. The replay (D2)

A repeat doesn't need a stored response. The transfer row is append-only and carries the key, so the service reads it back and the controller builds the response from it, exactly as it built the original. The response gets the same status (201), the same `Location`, the same body, and one extra header: `Idempotent-Replayed: true`. A replay moves nothing and writes no audit row, because nothing happened.

Because claims never change, the app has **no UPDATE right** on `idempotency_keys`. A trigger also rejects an UPDATE by any role, to catch mistakes.

---

## 6. The same key, at the same moment

Two identical requests arrive together. Both try to insert the claim. One gets there first. The other's insert **waits** on the first's uncommitted row, before it has touched any account:
- **The first commits:** the second's insert does nothing, it reads the claim, the fingerprints match, and it replays the first's transfer. (Before M6, the second could get 422 "insufficient funds" if the first had spent the money.)
- **The first rolls back** (it failed): the second's insert goes in, and it carries the transfer out itself.
- **The first takes longer than the lock timeout:** the second gives up with **409 `request-in-progress`** and `Retry-After: 1`. Its outcome isn't known yet, so it isn't "done". Before M6 this was a 503 `account-busy`, though no account was busy.

**The lock timeout covers the claim too.** M5 promised that no request waits on a lock for more than 2 seconds. The claim is now the first statement that can wait, so it sets `lock_timeout` before inserting, just as the posting service does.

**No new deadlocks.** A transaction takes exactly one claim, always before any account lock. So it never holds an account while waiting for a claim, and the M5 lock order still holds.

**A subtlety with error codes.** The in-progress error comes from a lock timeout (SQLSTATE `55P03`), and `RetryingTransactions` turns any `55P03` it finds among an exception's causes into "account busy". So `RequestInProgressException` deliberately doesn't carry the database error as its cause. A test checks that it reaches the caller unchanged.

---

## 7. 409 now has two meanings

| Problem type | Meaning | What the client does |
|---|---|---|
| `duplicate-request` | Done, but too long ago to replay (the claim expired) | Look the original up by `originalId` |
| `request-in-progress` | Not finished yet; may still succeed or fail | Wait `Retry-After`, retry with the same key and body |

A client must branch on `type`, not just on 409. Treating every 409 as "done" would drop a request that later fails. That's the same danger that made M5 choose 503 for a busy account.

| Situation | Answer |
|---|---|
| Same key, same request, original succeeded | 201, replayed |
| Same key, different request (or a transfer's key reused for a funding) | 422 `idempotency-key-reused` |
| Same key, original still running after 2 seconds | 409 `request-in-progress` |
| Same key, original failed | runs again |
| Same key, more than 24 hours later | 409 `duplicate-request` |

---

## 8. Expiry, cleanup, and the permanent backstop (D5)

Claims are kept for **at least 24 hours** (`LEDGER_IDEMPOTENCY_RETENTION`). After that, a scheduled job deletes them, 1,000 at a time, so each delete is a short transaction. A claim is honoured until it's actually deleted.

After expiry, the transfer row still has its key and its unique constraint, the **permanent backstop**. A late retry claims the key afresh, then finds the old transfer row and gets 409 `duplicate-request`, before any money moves.

That's why giving the app DELETE on claims is safe: even if every claim were deleted, by a bug or by an attacker through the app, a retry could lose its replay but could never debit twice. A test proves it with the claim layer switched off entirely (`UniqueKeyBackstopIT`).

---

## 9. Testing it

- **Races without sleeping:** a test holds the first request's transaction open (or holds a claim row open as the owner), waits until `pg_locks` shows the second request blocked, then commits, rolls back, or outlasts the lock timeout. The same technique as primer 05.
- **Each layer on its own:**
  - The claim: `IdempotencyKeysIT`.
  - The table's own guards: `IdempotencyKeysSchemaIT`.
  - The unique-key backstop: `UniqueKeyBackstopIT`, with the claim layer replaced by a mock that lets everything through.
  - The fingerprint: unit and property tests.
- **Telling layers apart:** after a claim is deleted, the service looks for the key on the transfer row before posting, and the unique key would also stop the retry later. To show which one answered, the tests make the late path fail *differently*. The money is already spent, so a retry that skipped the early check would get "insufficient funds" instead of 409. For a funding, the account is held locked, so a retry that skipped the check would time out as "busy".
- **Under load:** `ConcurrencyIT` repeats one request in ten. Every repeat must replay exactly the transfer its original made, nothing may be carried out twice, and claims, business rows, and audit rows must match one to one.

---

## Further reading
- PostgreSQL, `INSERT … ON CONFLICT`: https://www.postgresql.org/docs/current/sql-insert.html#SQL-ON-CONFLICT
- PostgreSQL, READ COMMITTED and what each statement sees: https://www.postgresql.org/docs/current/transaction-iso.html#XACT-READ-COMMITTED
- PostgreSQL, savepoints: https://www.postgresql.org/docs/current/sql-savepoint.html
- IETF draft, "The Idempotency-Key HTTP Header Field": https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/
- Stripe, idempotent requests: https://docs.stripe.com/api/idempotent_requests
- Brandur Leach, "Implementing Stripe-like Idempotency Keys in Postgres": https://brandur.org/idempotency-keys
