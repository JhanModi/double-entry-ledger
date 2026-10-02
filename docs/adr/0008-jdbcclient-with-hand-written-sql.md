# ADR-0008: `JdbcClient` with hand-written SQL

- **Status:** Accepted. The M4 open question below was resolved on 2026-10-02: **no JPA**. `JdbcClient` is used everywhere, including for `api_clients` and `api_keys`.
- **Date:** 2026-10-01

## Context
The most interview-relevant parts of this system are locking, isolation, and exact writes. They must be visible in the code, not hidden by a framework.

## Options considered
- **JPA/Hibernate (Spring Data JPA).** The most common choice in Java shops. It hides SQL, flush timing, dirty checking, and entity caching. Example risk: an entity loaded *before* a lock isn't refreshed after the lock, so code can act on a stale balance.
- **jOOQ.** Type-safe SQL, but it needs a code-generation step and more setup.
- **Spring `JdbcClient` with hand-written SQL.** Explicit with minimal magic. More boilerplate (row mappers).

## Decision
Use `JdbcClient` and hand-written SQL for all ledger and money code.

**Open question for M4:** use Spring Data JPA for simple non-ledger tables (e.g., `api_clients`), to show both approaches?
- **For:** shows you know the mainstream Java tool, and can explain when *not* to use it.
- **Against:** two data-access styles to learn at once. Also, within one transaction, changes JPA hasn't flushed yet are invisible to `JdbcClient` queries, which is a subtle class of bug.
- **If yes:** an ArchUnit rule forbids JPA in the `ledger`, `transfers`, and `payments` packages.

## Consequences
- Every lock and write is plain SQL you can read aloud in an interview.
- You learn SQL properly instead of through an abstraction.
- More boilerplate.
- No JPA practice, unless the M4 question is answered yes.

## How to explain it
"For the ledger I wrote the SQL myself, because the correctness story (row locks, deltas, constraints) needs to be visible and reviewable. An ORM's flush and caching behavior can quietly introduce stale reads in exactly the code where that matters most."
