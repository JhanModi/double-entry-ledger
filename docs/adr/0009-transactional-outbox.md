# ADR-0009: Transactional outbox, Kafka later

- **Status:** Accepted
- **Date:** 2026-10-01

## Context
Other components (webhook notifications, later Kafka consumers) need to know when money moves. Writing to the database and publishing to a broker are two separate systems, which creates a **dual-write** problem:
- **Commit, then publish:** a crash in between loses the event.
- **Publish, then commit:** a rollback leaves a "phantom" event for something that never happened.

## Options considered
- **Publish directly to Kafka during the request.** Has the dual-write problem.
- **Transactional outbox.** Write the event as a row in the same transaction; a relay publishes it afterward.
- **Change data capture** (e.g., Debezium reading the Postgres write-ahead log). Powerful, but heavy infrastructure.

## Decision
- **Table:** `outbox_events(id, aggregate_type, aggregate_id, aggregate_version, event_type, payload jsonb, created_at, published_at)`, written in the same transaction as the change it describes.
- **Relay:** a *single* relay (leadership via a Postgres advisory lock) selects `WHERE published_at IS NULL ORDER BY id`, publishes, then sets `published_at`.
  - It doesn't use a "last published id" high-water mark. A lower id can commit *after* a higher one, and a high-water mark would skip it forever.
- **Message keys:** messages are keyed by `aggregate_id`, so all events for one payment stay in order.
- **Consumers** dedupe through a `processed_events` table and drop events with a stale `aggregate_version`. Delivery is at-least-once.
- **Before Kafka (M10–M12):** an `EventPublisher` interface with an in-process implementation.
- **Kafka (M13):** chosen over RabbitMQ because its replayable log and per-key ordering suit ledger events, and it's common at large fintechs.

## Consequences
- No lost events and no phantom events.
- The broker is pluggable.
- Events are delayed by the relay's poll interval.
- At-least-once delivery means every consumer must be idempotent.
- Published rows need a retention policy (decided later). They aren't financial records.

## How to explain it
"Instead of writing to the database and then to Kafka, which can half-fail, I write the event into an outbox table in the same transaction as the money movement. A single relay publishes it afterward. Delivery is at-least-once, so consumers deduplicate by event id."
