# double-entry-ledger

A double-entry ledger and payments API in Java and Spring Boot.

> **Status: early development.** What exists today:
> - the double-entry ledger core
> - least-privilege database roles
> - an authenticated API for opening accounts and reading balances and history
>
> Transfers over the API come next. See the [roadmap](docs/roadmap.md).

## Prerequisites

- JDK 25
- Docker (Docker Desktop on Windows and macOS). The integration tests and the local database both need it running.

Maven doesn't need to be installed: the Maven Wrapper (`mvnw`) downloads the pinned version on first use. In PowerShell, use `.\mvnw` instead of `./mvnw`.

## Run locally

1. Create your local config, then replace the placeholder passwords in `.env`:
   ```bash
   cp .env.example .env
   ```
   If port 5432 is already in use (for example by a PostgreSQL installed directly on your machine), set `DB_PORT=5433` in `.env`. Docker Compose and the app both read it.
2. Start Postgres:
   ```bash
   docker compose up -d
   ```
   On its first start (an empty data volume), Postgres runs `docker/postgres/initdb/`. That creates the restricted login the app connects as, from `APP_DB_USER` and `APP_DB_PASSWORD` in `.env`. Flyway migrations run as the owner. If you set up your database before these roles existed, reset it once with `docker compose down -v`, which **deletes all local data**.
3. Start the app. Flyway applies database migrations on startup.
   ```bash
   ./mvnw spring-boot:run
   ```
4. Check health:
   ```bash
   curl http://localhost:8080/actuator/health
   ```
   Expected: HTTP 200 with `"status":"UP"` in the body.

## Use the API

1. Create a client and its first API key with the app's command-line mode. It prints the key **once**, so store it safely.
   ```bash
   ./mvnw spring-boot:run "-Dspring-boot.run.arguments=clients create --name=acme --scopes=read,write"
   ```
2. With the app running, open a USD account and read it back:
   ```bash
   curl -X POST http://localhost:8080/v1/accounts -H "Authorization: Bearer $API_KEY" -H "Content-Type: application/json" -d '{"currency":"USD"}'
   ```
   ```bash
   curl http://localhost:8080/v1/accounts/$ACCOUNT_ID -H "Authorization: Bearer $API_KEY"
   ```
   ```bash
   curl "http://localhost:8080/v1/accounts/$ACCOUNT_ID/entries?limit=50" -H "Authorization: Bearer $API_KEY"
   ```

Amounts are integer minor units plus a currency: `{"amount": 1050, "currency": "USD"}` is $10.50. Errors use RFC 9457 Problem Details. Another client's account returns 404, exactly like one that doesn't exist.

## Test

```bash
./mvnw verify
```

This compiles, runs unit tests (`*Test`) and integration tests (`*IT`, which start Postgres in Docker through Testcontainers), and checks formatting. CI runs the same command.

- `./mvnw test` runs only the unit tests. It's fast and doesn't need Docker.
- `./mvnw spotless:apply` fixes formatting ([Palantir Java Format](https://github.com/palantir/palantir-java-format)).

## Design docs

- [Design](docs/design.md): architecture, invariants, failure modes
- [Architecture decision records](docs/adr/README.md)
- [Glossary](docs/glossary.md)
