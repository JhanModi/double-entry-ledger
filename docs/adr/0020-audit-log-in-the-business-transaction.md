# ADR-0020: An append-only audit log, written in the business transaction

- **Status:** Accepted
- **Date:** 2026-10-02
- **Corrected:** 2026-10-02, at the owner's request: the wording on what the triggers protect against. The decision is unchanged.

## Context
`CLAUDE.md` requires an append-only audit log of security-relevant and financial actions. Each record says who did it (the actor), what they did (the action), what it was done to (the target), when, and where the request came from (the origin). An auditor must be able to trust two things: every action that happened is in the log, and nothing is in the log that didn't happen.

## Options considered
- **Log lines only.** Easy, but log lines aren't queryable, aren't append-only, and get rotated away.
- **An audit table written in its own transaction** (`REQUIRES_NEW`), or after the business commit. The two can disagree: a crash between them loses the audit row, or an audit row survives while the business change rolls back.
- **An audit table written in the same transaction as the action.** Both commit, or neither does.
- **Derive the audit trail from the business tables.** They don't record the source IP or the key used, and actions such as issuing a key have no business row to derive from.

## Decision
- **The `audit_log` table (V5).** Each row holds:
  - `occurred_at`: the database time, the same clock as every other `created_at`
  - `action`
  - `target_id`: the target's public identifier. That's the UUID for accounts, transfers, fundings, and clients, and the public key id for API keys.
  - the actor
  - the origin: the request id (ADR-0021) and the source IP
- **Actors** are an API key (its client and its public key id) or the operator using the command line (`OPERATOR_CLI`), which has no client, key, request, or IP.
  - A CHECK requires exactly the right details for each kind of actor.
  - A composite foreign key ensures the key belongs to the stated client.
- **Actions in M4b:** `ACCOUNT_OPENED`, `TRANSFER_CREATED`, `FUNDING_CREATED`, `CLIENT_CREATED`, `API_KEY_ISSUED`.
- **Written through `AuditLog.record`,** which is `@Transactional(propagation = MANDATORY)`: it throws if no transaction is already open. So an audit row commits if and only if the action it describes commits.
- **Only committed actions are audited.** Rejected attempts (insufficient funds, 401, 403) go to the application log with their request id. Auditing them would need a second transaction, and an attacker could flood the table.
- **Append-only, in two layers (ADR-0015):**
  - **Triggers** reject UPDATE, DELETE, and TRUNCATE whichever role runs them. That catches *mistakes*, such as a bug or a hand-written fix. The owner or a superuser can still switch the triggers off on purpose.
  - **Privileges** stop the app: it may only `INSERT`, so it can write the log but can't read it, change it, or delete from it. It can't switch the triggers off either, because only the table's owner or a superuser can.
- **No free-form detail column.** Every column has a type, so secrets and personal data can't slip in through a JSON blob.

## Consequences
- **The audit log and the business data agree by construction.**
- **Even a compromised app can't read or rewrite audit history.** Reading it is an owner or operator task.
- **Rejected attempts are only in the application log,** so a burst of failures is visible there and not in the audit table.
- **The source IP is the TCP peer.** Behind a proxy it would be the proxy's address. Trusting `X-Forwarded-For` is a deployment decision (M16).
- **The table grows without limit.** Retention is a later concern.
- **The triggers don't stop the owner or a superuser** (the ADR-0015 trust model). The owner can `ALTER TABLE audit_log DISABLE TRIGGER ALL` or drop the table, and a superuser can `SET session_replication_role = replica`, which stops ordinary triggers firing. Protecting the log against them needs controls outside the database.

## How to explain it
"The audit row is written in the same database transaction as the action, and the method that writes it refuses to run outside a transaction. So the audit log can never claim something happened that didn't, or miss something that did. The app's database login can insert into the audit table but can't read, update, or delete it. Triggers also reject updates and deletes by any role, which catches mistakes, but they aren't a defense against the owner or a superuser, who can switch them off on purpose."
