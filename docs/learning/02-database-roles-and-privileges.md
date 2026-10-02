# Primer: database roles and privileges

M3b learning notes. Read this before writing the GRANT statements in `V3__grant_app_privileges.sql`. The syntax examples use a made-up shop, so they don't write V3 for you. The rules V3 must satisfy are in `AppRolePrivilegesIT`.

---

## 1. Roles: users and groups are the same thing

In Postgres a **role** can be a user, a group, or both. Two attributes make the difference:

```sql
CREATE ROLE shop_app NOLOGIN;                                   -- a group: nobody logs in as it
CREATE ROLE shop_web LOGIN PASSWORD '...' IN ROLE shop_app;     -- a user that is a member of the group
```

A member **inherits** the group's privileges (that's the default). So you grant privileges to the group once, and every login in it gets them.

**Why bother with a group:** to rotate a password, create `shop_web_v2` in the same group, switch the application over, then drop `shop_web`. Nothing has to be re-granted, and there's no downtime.

---

## 2. Three levels of power

| Level | Can do | How you get it |
|---|---|---|
| **Superuser** | Anything at all; privilege checks are skipped entirely | `CREATE ROLE … SUPERUSER` |
| **Owner** of a table | Anything to *that table*: alter it, drop it, disable its triggers, drop its constraints, grant on it | Whoever creates the table owns it |
| **Granted privileges** | Exactly the operations granted, nothing more | `GRANT` from the owner |

Two consequences matter for this project:
- **Triggers and constraints don't protect against the owner or a superuser.** The owner can run `ALTER TABLE entries DISABLE TRIGGER ALL`. A superuser can `SET session_replication_role = replica`, which stops ordinary triggers from firing at all. Triggers protect against *mistakes*.
- **Privileges are what stop the application.** An application that is neither the owner nor a superuser can only do what it was granted. No `GRANT` can give someone ownership or superuser powers, so no grant can let the app disable triggers or switch on replica mode.

---

## 3. Table privileges

| Privilege | Allows |
|---|---|
| `SELECT` | reading rows (and reading columns in a `WHERE` or `SET` expression) |
| `INSERT` | adding rows |
| `UPDATE` | changing rows (can be limited to specific columns) |
| `DELETE` | removing rows |
| `TRUNCATE` | emptying the table in one go |
| `REFERENCES`, `TRIGGER` | creating foreign keys / triggers that point at it (rarely granted to apps) |

---

## 4. GRANT syntax

```sql
-- One privilege on one table:
GRANT SELECT ON products TO shop_app;

-- Several privileges, several tables, one statement:
GRANT SELECT, INSERT ON orders, order_lines TO shop_app;

-- Column-level: UPDATE allowed only on the listed columns. Every other column stays read-only.
GRANT UPDATE (status, shipped_at) ON orders TO shop_app;

-- Taking a privilege back:
REVOKE DELETE ON orders FROM shop_app;
```

A few things to know:
- **An UPDATE also needs `SELECT`** on the columns it reads. `UPDATE orders SET total = total + 1 WHERE id = 7` reads `total` and `id`, so a table-level `SELECT` covers that.
- **`PUBLIC`** is a pseudo-role meaning "everyone." Since Postgres 15, `PUBLIC` can no longer create tables in the `public` schema, so a restricted login can't create tables unless someone grants it that.
- **`GRANT ALL`** grants every table privilege. It's almost never what an application should have.
- **Foreign-key checks** run with the table owner's rights, so the app doesn't need extra privileges for them.

---

## 5. Seeing what's granted

- In `psql`: `\dp accounts` shows a table's privileges. In the output, `r` = SELECT, `a` = INSERT, `w` = UPDATE, `d` = DELETE, `D` = TRUNCATE.
- In SQL: `has_table_privilege('shop_app', 'orders', 'DELETE')` returns true or false.
- The raw data lives in `pg_class.relacl` (table level) and `pg_attribute.attacl` (column level). `aclexplode()` turns it into rows. That's what the inventory tests in `AppRolePrivilegesIT` read.

When a privilege is missing, Postgres fails with **SQLSTATE 42501** (`insufficient_privilege`): "permission denied for table …" or "must be owner of table …". The denial tests check for exactly that code, so a statement blocked for some *other* reason (for example by a trigger) doesn't count as a denial.

---

## Further reading (official PostgreSQL docs)
- Roles and membership: https://www.postgresql.org/docs/current/user-manag.html
- Privileges: https://www.postgresql.org/docs/current/ddl-priv.html
- GRANT: https://www.postgresql.org/docs/current/sql-grant.html
- session_replication_role: https://www.postgresql.org/docs/current/runtime-config-client.html#GUC-SESSION-REPLICATION-ROLE
