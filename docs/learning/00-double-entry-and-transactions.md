# Primer: double-entry bookkeeping and database transactions

M0 learning notes. Read this before M1. The teach-back questions are at the end. The SQL here is illustrative and simplified, not the final schema.

---

## Part 1: Double-entry bookkeeping

### The idea
Every movement of money is recorded **twice**: where it came from and where it went. It works like a conservation law. Inside the ledger, money is never created or destroyed, only moved. If the two sides ever stop adding up, you know immediately that something is wrong.

### Debit and credit are just "left" and "right"
They don't mean "bad" and "good," or even "decrease" and "increase." Whether a debit raises or lowers a balance depends on the account's **type**:

| Account type | Normal side (what increases it) | Example in this project |
|---|---|---|
| Asset | Debit | Bank settlement: cash the platform holds at its partner bank |
| Liability | Credit | Customer wallets: money the platform *owes* its customers |
| Revenue | Credit | FX spread and fee income |
| Expense | Debit | (not used yet) |
| Equity | Credit | (not used yet) |

**Why is a customer's wallet a liability?** When Alice deposits $100, the platform holds $100 at its bank (an asset) and *owes* Alice $100 (a liability). Her balance is a debt the platform must repay on request.

### Worked example (amounts in cents, i.e., minor units)

**1. Alice deposits $100** (an inbound payment settles)

| Account | Debit | Credit |
|---|---|---|
| Bank settlement (asset) | 10000 | |
| Alice wallet (liability) | | 10000 |

**2. Alice sends Bob $30** (internal transfer)

| Account | Debit | Credit |
|---|---|---|
| Alice wallet | 3000 | |
| Bob wallet | | 3000 |

**3. Bob withdraws $10** (outbound payment settles)

| Account | Debit | Credit |
|---|---|---|
| Bob wallet | 1000 | |
| Bank settlement | | 1000 |

**Resulting balances**
- Bank settlement (debit-normal): 10000 − 1000 = **9000**
- Alice (credit-normal): 10000 − 3000 = **7000**
- Bob (credit-normal): 3000 − 1000 = **2000**

**Checks**
- Each transaction's debits equal its credits.
- Total debits (14000) equal total credits (14000).
- Assets (9000) = liabilities (7000 + 2000). This is the accounting equation, *assets = liabilities + equity*.

### Fixing a mistake without editing history
Suppose step 2 should have been $3, not $30. We **never** edit or delete the original entries. Instead we post two new transactions:

| Transaction | Account | Debit | Credit |
|---|---|---|---|
| Reversal of step 2 | Bob wallet | 3000 | |
| | Alice wallet | | 3000 |
| Correct transfer | Alice wallet | 300 | |
| | Bob wallet | | 300 |

The ledger now tells the full story: what happened, that it was wrong, and how it was fixed. That's what an auditor needs. If you edited the original row instead, nobody could tell the mistake had ever happened, or prove it hadn't been tampered with.

---

## Part 2: Database transactions

### All or nothing
A **transaction** groups several statements so that either all of them happen or none of them do:

```sql
BEGIN;
INSERT INTO entries (...) VALUES (... 'DEBIT', 3000 ...);   -- Alice
INSERT INTO entries (...) VALUES (... 'CREDIT', 3000 ...);  -- Bob
UPDATE accounts SET posted_balance = posted_balance - 3000 WHERE id = :alice;
UPDATE accounts SET posted_balance = posted_balance + 3000 WHERE id = :bob;
COMMIT;
```

- **COMMIT** makes all of it permanent at once.
- If anything fails before COMMIT, it's **rolled back** as if it never happened.
- **If the server crashes after the first INSERT,** the transaction never committed. Its changes were never visible to anyone, and PostgreSQL discards them during recovery. There's no "half a transfer." This is why internal transfers in this project are a single transaction.

### Isolation isn't enough on its own
Other transactions can't see your uncommitted changes. But that alone doesn't stop a **race**. Here are two $80 withdrawals from a $100 account at the same moment, under PostgreSQL's default isolation (READ COMMITTED):

| Time | Request A | Request B |
|---|---|---|
| t1 | reads balance: 100 | |
| t2 | | reads balance: 100 |
| t3 | 100 ≥ 80 ✓, subtracts 80, commits | |
| t4 | | 100 ≥ 80 ✓ (stale!), subtracts 80, commits |

$160 left an account that held $100. Both requests checked funds, but against a value that was already out of date.

### The fix: lock the row first

```sql
SELECT posted_balance FROM accounts WHERE id = :alice FOR NO KEY UPDATE;
```

| Time | Request A | Request B |
|---|---|---|
| t1 | locks row, reads 100 | |
| t2 | | tries to lock → **waits** |
| t3 | subtracts 80, commits (lock released) | |
| t4 | | gets lock, reads **20** → 20 < 80 → rejected |

The lock makes "check, then write" behave as one step for each account. This is [ADR-0005](../adr/0005-pessimistic-row-locking.md).

### Deadlocks, and why lock order matters
A transfer A→B locks A and then wants B. At the same moment, a transfer B→A locks B and then wants A. Each waits for the other forever. That's a **deadlock**. (PostgreSQL detects it and aborts one of them.) The fix is to always lock accounts in the same order (lowest id first), so two transfers can never each hold the lock the other needs.

---

## Further reading (official PostgreSQL docs)
- Transactions tutorial: https://www.postgresql.org/docs/current/tutorial-transactions.html
- Transaction isolation: https://www.postgresql.org/docs/current/transaction-iso.html
- Explicit locking: https://www.postgresql.org/docs/current/explicit-locking.html

---

## Teach-back questions (answer in your own words before M1)

1. Why does every ledger transaction need at least two entries, and what does "balanced" mean?
2. Why is a customer's wallet a *liability* for the platform? Does a credit increase or decrease it?
3. Alice's $30 transfer to Bob was a mistake. How do we fix it without editing history, and why don't we just edit the row?
4. The server crashes after inserting Alice's debit entry but before inserting Bob's credit entry. What does the database contain after restart, and why?
5. Two $80 withdrawals hit a $100 account at the same instant. Explain how this goes wrong without locking, and how locking the row prevents it.
