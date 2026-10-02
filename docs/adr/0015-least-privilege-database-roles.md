# ADR-0015: Least-privilege database roles

- **Status:** Accepted
- **Date:** 2026-10-02

## Context
Until M3b the application connected to Postgres as the owner, which is a superuser locally and in tests. Any bug in the app, or any SQL an attacker tricked it into running, could do anything: rewrite ledger history, disable the triggers, drop tables, or edit Flyway's migration history.

It helps to be precise about what each layer of protection actually does:

- **Constraints and triggers (V2) catch mistakes by any role**: a bug, a hand-written fix, a wrong migration. They are *not* a security boundary against the table owner or a superuser.
  - The owner can `ALTER TABLE … DISABLE TRIGGER` or drop a constraint.
  - A superuser can `SET session_replication_role = replica`, which stops ordinary triggers from firing, foreign-key checks included.
- **Privileges stop the application.** If the app's login isn't the owner and isn't a superuser, then whatever SQL it's tricked into running, it can't change history or switch the guards off.
- **The owner is trusted, and used for migrations only.** Protection against a malicious owner or DBA has to come from outside the database:
  - who holds the credentials
  - auditing schema changes (e.g., pgaudit)
  - backups
  - reconciliation against an external record (M11)

## Options considered
- **One role for everything** (the status quo). Simple, but any app bug is a full compromise.
- **A single restricted login with privileges granted directly to it.** Simpler, but rotating credentials means re-granting everything to the new login.
- **A group role that holds the privileges, plus login roles that are members of it.** Rotate by creating a new login in the group, switching the app over, and dropping the old login. Nothing is re-granted.
- **Row-level security** (per-tenant row filtering in the database). Finer-grained, but out of scope for now. Tenant isolation is enforced in the application from M4, and RLS could be added later as a second layer.

## Decision
| Role | Created by | Purpose |
|---|---|---|
| Owner (`POSTGRES_USER`) | The environment | Owns every table and runs Flyway migrations. Nothing else. |
| `ledger_app` (NOLOGIN) | The environment | Holds the application's privileges. |
| `ledger_service` (LOGIN, member of `ledger_app`) | The environment | What the application connects as. |

- **The environment creates the roles; migrations only grant privileges.** Roles and passwords are infrastructure, and migrations are committed to git.
  - Locally: `docker/postgres/initdb/10-create-app-roles.sh`, which passes the password as a psql variable, so quotes can't break or inject into the SQL.
  - In tests: `src/test/resources/db/testcontainers-roles.sql`.
  - In production: infrastructure tooling.
- **`V3__grant_app_privileges.sql`** grants `ledger_app`:
  - `SELECT` on `currencies`
  - `SELECT` and `INSERT` on `accounts`, `ledger_transactions`, and `entries`
  - `UPDATE` on only `accounts.posted_balance`, `held_balance`, and `status` (column-level grants)
  - nothing else: no `UPDATE`, `DELETE`, or `TRUNCATE` on history, nothing on `flyway_schema_history`, and no `CREATE`
- **Separate connections:** the application's datasource uses the restricted login; Flyway uses the owner on a separate connection.
- **Tests mirror this:**
  - `TestcontainersConfiguration` wires the two connections explicitly.
  - `OwnerDatabase` gives the owner connection to the few tests that need it (`LedgerSchemaIT`).
  - `AppRolePrivilegesIT` pins the exact privilege inventory and proves each denial fails with SQLSTATE 42501. The denials include `ALTER TABLE … DISABLE TRIGGER ALL` and `SET session_replication_role = replica`.
- **Every migration that adds a table also grants its privileges and updates the inventory in `AppRolePrivilegesIT`.** Privileges are a reviewed decision, never an accident.

## Consequences
- **Contained:** an app bug or SQL injection through the app's connection can't edit ledger history, change what an account is, disable triggers, change `session_replication_role`, alter or drop schema objects, or touch migration history.
- **Known gap: the application process still holds the owner's credentials**, because Flyway runs at startup.
  - SQL injection through the app's *connection* is contained.
  - But an attacker who can execute code in the app *process*, or read its environment, could connect as the owner.
  - The fix is to run migrations as a separate deployment step (a CI/CD job or an init container), so the running app never sees owner credentials. Tracked for M16.
- **Locally and in tests, the owner is a superuser** (the Postgres image's default). In production, the migration owner should be a non-superuser role that owns the schema.
- **The local database had to be reset once,** because init scripts only run on an empty volume.
- **Adding a table now takes an explicit grant.** That friction is intentional.

## How to explain it
"The app connects as a role that can only read and insert ledger rows and update three columns on accounts. It isn't the owner or a superuser, so even if it's tricked into running arbitrary SQL, it can't edit history, disable the triggers, or switch on replica mode, and tests prove each of those denials. The triggers are there to catch mistakes, not to stop a malicious owner; the owner is trusted and used only for migrations. One gap remains: Flyway still runs at startup with the owner's credentials, so the next step is moving migrations into a separate deployment job."
