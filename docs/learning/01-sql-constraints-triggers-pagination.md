# Primer: SQL constraints, triggers, aggregation, and pagination

M3a learning notes. Read this before writing the invariant checker's SQL. The examples use a made-up shop (customers and orders) so they teach the ideas without solving the exercise. The ledger's real schema is in `src/main/resources/db/migration/V2__create_ledger.sql`.

---

## 1. Constraints: rules the database enforces itself

A constraint is a rule the database checks on every write, whichever program does the writing. That's why the ledger's most important rules live here as well as in Java: a bug, a hand-written SQL fix, or a future service can't get around them.

| Constraint | Means | Example in V2 |
|---|---|---|
| `NOT NULL` | a value is required | `entries.amount BIGINT NOT NULL` |
| `CHECK (...)` | the expression must not be false | `CHECK (amount > 0)` |
| `PRIMARY KEY` | unique and not null; how a row is identified | `accounts.id` |
| `UNIQUE` | no two rows may share the value(s) | `UNIQUE (id, currency)` on accounts |
| `FOREIGN KEY` / `REFERENCES` | the value must exist in another table | `entries.transaction_id REFERENCES ledger_transactions (id)` |

**A composite foreign key** points at two columns at once. `entries (account_id, currency) REFERENCES accounts (id, currency)` means an entry's currency must be the same as its account's currency. A plain foreign key on `account_id` alone couldn't express that.

**Name your constraints.** When `CONSTRAINT accounts_available_balance_non_negative CHECK (...)` is violated, its name appears in the error message, so the log says exactly which rule was broken. Unnamed constraints get generated names like `entries_amount_check`. (Until M5, `PostingService` matched this name to turn an overdraft into an `InsufficientFundsException`. Since M5 it checks funds itself, under the lock, so this constraint firing means a bug, and its error is left as a 500. See primer 05.)

---

## 2. NULL: the value that isn't a value

`NULL` means "unknown," and comparisons with unknown are unknown:

| Expression | Result |
|---|---|
| `5 = 5` | `TRUE` |
| `5 = NULL` | `NULL` (not FALSE!) |
| `NULL = NULL` | `NULL` |
| `NULL <> 5` | `NULL` |
| `5 IS DISTINCT FROM NULL` | `TRUE` (a NULL-safe "not equal") |
| `coalesce(NULL, 0)` | `0` (the first non-NULL argument) |

Two rules follow, and both matter in this project:

- **`WHERE` and `HAVING` keep a row only when the condition is `TRUE`.** A row whose condition is `NULL` silently disappears, with no error.
- **`CHECK` rejects a row only when the condition is `FALSE`.** `NULL` passes. That's why system accounts, whose balance columns are NULL, pass `CHECK (posted_balance - held_balance >= 0)`. Here the NULL is intentional.

**Aggregates and NULL:** `sum(x)` over *zero rows* is `NULL`, not `0`. "The total of nothing" is unknown to SQL.

---

## 3. Joins

Shop tables: `customers(id, name)` and `orders(id, customer_id, total)`. Ann has two orders. Ben has none.

**INNER JOIN** keeps only rows that have a match on both sides:

```sql
SELECT c.name, o.total
FROM customers c
JOIN orders o ON o.customer_id = c.id;
-- Ann | 30
-- Ann | 20          (Ben is gone: he has no orders)
```

**LEFT JOIN** keeps every row from the left table. Where nothing matches, the right side's columns are NULL:

```sql
SELECT c.name, o.total
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id;
-- Ann | 30
-- Ann | 20
-- Ben | NULL        (kept, with no order)
```

So pick the join type by asking: *should rows without a match still count?*

---

## 4. Aggregation: GROUP BY, HAVING, CASE, FILTER

`GROUP BY` collapses rows into one row per group, and aggregate functions (`sum`, `count`, `min`, `max`) summarise each group:

```sql
SELECT c.name, count(o.id) AS order_count, coalesce(sum(o.total), 0) AS spent
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id
GROUP BY c.id, c.name;
-- Ann | 2 | 50
-- Ben | 0 | 0       (count ignores NULLs; sum would be NULL without coalesce)
```

**`WHERE` vs `HAVING`:**
- `WHERE` filters *rows before* grouping. It can't use aggregates.
- `HAVING` filters *groups after* grouping. It can.

```sql
-- Customers who have spent more than 40:
SELECT c.name, sum(o.total) AS spent
FROM customers c JOIN orders o ON o.customer_id = c.id
GROUP BY c.id, c.name
HAVING sum(o.total) > 40;
```

**Conditional aggregation** totals only some of a group's rows. There are two ways to write it:

```sql
-- Orders over 25 versus the rest, per customer:
SELECT customer_id,
       sum(CASE WHEN total > 25 THEN total ELSE 0 END) AS big_orders,   -- CASE: standard SQL
       sum(total) FILTER (WHERE total <= 25)            AS small_orders  -- FILTER: Postgres shorthand
FROM orders
GROUP BY customer_id;
```

Watch out: with `FILTER`, a group that has *no* matching rows sums to `NULL` (sum of nothing). With `CASE … ELSE 0`, it's `0`, because every row contributes something.

**Numeric types:** in Postgres, `sum()` over a `BIGINT` column returns `NUMERIC`. A total of many large amounts can exceed a 64-bit number, so Postgres widens it. The Java code reads these as exact whole numbers.

**Grouping by a primary key:** once you `GROUP BY c.id` (a primary key), Postgres lets you select the other columns of `c` without listing them, because they can only have one value per id.

---

## 5. Triggers and deferred checks

A **trigger** runs a function automatically when rows change. Two choices define it:
- **When:** `BEFORE` the change (it can reject it, or modify the row) or `AFTER` it.
- **How often:** `FOR EACH ROW` or `FOR EACH STATEMENT`.

V2 uses `BEFORE UPDATE OR DELETE … FOR EACH ROW` triggers that simply raise an error. That's how entries become append-only. It uses `BEFORE TRUNCATE … FOR EACH STATEMENT` for `TRUNCATE`, which empties a table without touching individual rows.

**The timing problem:** "a transaction's debits equal its credits" can't be checked after each `INSERT`. After the first entry it's always unbalanced. The check has to wait until every entry is in.

**Constraint triggers** solve it: `CREATE CONSTRAINT TRIGGER … DEFERRABLE INITIALLY DEFERRED` queues the check and runs it at `COMMIT`. If the check fails, the commit fails and everything in the transaction is rolled back. In Java, that error arrives when Spring commits, *after* your method has returned, so it surfaces from the transaction machinery rather than from the `INSERT`.

**The gap a single trigger leaves:** a trigger on `entries` only fires when an entry is inserted. A ledger transaction with *zero* entries never fires it. So V2 has a second deferred trigger on `ledger_transactions` itself. When you add a safety check, always ask: "what if the thing that triggers my check never happens?"

---

## 6. Isolation: why the checker uses REPEATABLE READ

The invariant checker runs two queries. Under Postgres's default, **READ COMMITTED**, each *statement* sees the data committed when that statement started. If a posting commits between the two queries, they'd describe two different moments, and the checker could report a mismatch that never existed.

**REPEATABLE READ** gives the whole transaction a single snapshot. Every query sees the database exactly as it was when the transaction began, whatever commits in the meantime. It's also `readOnly`, so it can never change anything by accident.

---

## 7. Pagination: OFFSET versus keyset

**OFFSET pagination** (`ORDER BY id DESC LIMIT 20 OFFSET 400`) is the obvious approach, with two problems:
1. **Cost:** the database reads and throws away the first 400 rows on every request. Page 1,000 is slow.
2. **Drift:** if a new row arrives while someone is paging, everything shifts by one. They'll see a row twice or miss one.

**Keyset pagination** remembers where the previous page ended:

```sql
SELECT * FROM orders
WHERE customer_id = :customer AND id < :last_id_seen   -- the cursor
ORDER BY id DESC
LIMIT 20;
```

With an index on `(customer_id, id)`, Postgres jumps straight to the cursor and reads 20 rows, however deep the page. New rows don't shift earlier pages. `LedgerQueries.history` works this way, and it asks for 21 rows: if the 21st comes back, there's another page.

**Indexes:** an index is a sorted copy of some columns that points back to the rows, like the index at the back of a book. V2's `entries (account_id, id)` index serves history pages and per-account totals. You can see whether Postgres uses an index by putting `EXPLAIN` in front of a query.

---

## Further reading (official PostgreSQL docs)
- Constraints: https://www.postgresql.org/docs/current/ddl-constraints.html
- Joins: https://www.postgresql.org/docs/current/queries-table-expressions.html
- Aggregate functions and FILTER: https://www.postgresql.org/docs/current/sql-expressions.html#SYNTAX-AGGREGATES
- Comparison and NULL handling: https://www.postgresql.org/docs/current/functions-comparison.html
- CREATE TRIGGER (including constraint triggers): https://www.postgresql.org/docs/current/sql-createtrigger.html
- Transaction isolation: https://www.postgresql.org/docs/current/transaction-iso.html
