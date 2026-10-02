# Architecture Decision Records

An ADR records one significant decision: the context, the options considered, what was chosen, and the consequences. ADRs are never edited after acceptance (except for typos and status). To change a decision, write a new ADR that supersedes the old one.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-modular-monolith.md) | Modular monolith with enforced module boundaries | Accepted |
| [0002](0002-money-as-integer-minor-units.md) | Money as `long` minor units + currency | Accepted |
| [0003](0003-entry-direction-and-positive-amount.md) | Entries use direction + positive amount | Accepted |
| [0004](0004-balances-as-cached-projection.md) | Balances are a cached projection of entries | Accepted |
| [0005](0005-pessimistic-row-locking.md) | Pessimistic row locking in a fixed order | Accepted |
| [0006](0006-holds-table.md) | Authorization holds in a holds table, with expiry | Accepted |
| [0007](0007-idempotency-in-the-business-transaction.md) | Idempotency recorded inside the business transaction | Accepted |
| [0008](0008-jdbcclient-with-hand-written-sql.md) | `JdbcClient` with hand-written SQL | Accepted (open question for M4) |
| [0009](0009-transactional-outbox.md) | Transactional outbox, Kafka later | Accepted |
| [0010](0010-in-process-mock-bank-and-recovery.md) | In-process mock bank and payment recovery | Accepted |
| [0011](0011-api-key-authentication.md) | API-key authentication with scopes | Accepted |
| [0012](0012-maven.md) | Maven as the build tool | Accepted |
| [0013](0013-currencies-as-a-java-enum.md) | Currencies as a Java enum, mirrored by the `currencies` table | Accepted |

## Template

```markdown
# ADR-NNNN: Title

- **Status:** Proposed | Accepted | Superseded by ADR-XXXX
- **Date:** YYYY-MM-DD

## Context
What problem are we solving, and what constraints apply?

## Options considered
Each option with its main pros and cons.

## Decision
What we chose, with enough detail to implement it.

## Consequences
What gets easier, what gets harder, and what we must watch for.

## How to explain it
Two or three sentences you could say in an interview.
```
