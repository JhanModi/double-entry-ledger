# ADR-0001: Modular monolith with enforced module boundaries

- **Status:** Accepted
- **Date:** 2026-10-01

## Context
The core guarantee of this system is that ledger entries, the idempotency record, and the outgoing event for an operation are committed together or not at all. The project is built by one person at portfolio scale.

## Options considered
- **Microservices** (ledger, payments, FX, reconciliation as separate services). Each can be deployed and scaled on its own. But every operation that spans services becomes a distributed transaction (sagas, compensations, eventual consistency), and there's much more infrastructure to run.
- **Modular monolith.** One deployable app and one Postgres database, split into modules with strict boundaries.
- **Unstructured monolith.** Fastest to start, but boundaries erode until everything depends on everything.

## Decision
Use a modular monolith: one Spring Boot application.
- **Domain modules:** `ledger`, `transfers`, `payments`, `fx`, `reconciliation`, `clients`.
- **Shared infrastructure:** `money`, `idempotency`, `outbox`, `audit`.
- ArchUnit tests enforce the allowed dependencies between modules, including the rule that only `ledger` writes ledger entries.

## Consequences
- One database transaction covers entries, idempotency, and the outbox, so atomicity comes free.
- One thing to run, debug, and test.
- Scaling is all-or-nothing. That's acceptable at this scale.
- Boundaries hold only if they're enforced, which is why the ArchUnit tests are required rather than optional.
- **Extraction path:** `payments` and the bank rail could become a separate service later, communicating through outbox events.

## How to explain it
"The key guarantee is that entries, the idempotency record, and the event commit atomically. That's trivial inside one database transaction and hard across services. So I built a modular monolith and enforced module boundaries with tests, which means a module can still be extracted if scale ever demands it."
