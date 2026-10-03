# double-entry-ledger

A double-entry ledger and payments API in Java and Spring Boot.

> **Status: early development.** What exists today:
> - the double-entry ledger core
> - least-privilege database roles
> - an authenticated API for opening accounts, reading balances and history, funding accounts, and moving money between a client's own accounts
> - an append-only audit log, and a request id on every response
> - safety under concurrency: accounts locked in one fixed order, a 2-second limit on waiting for a lock, deadlock retries, and a test that sends 1,000 requests at once while checking the ledger's invariants
> - safe retries: an idempotency key claimed in the same transaction as the money movement, so a retried request gets the original response and never moves money twice
>
> Next: the resume checkpoint (M7). See the [roadmap](docs/roadmap.md).

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

1. Create a client and its first API key with the app's command-line mode. It prints the key **once**, so store it safely. The `admin` scope is only needed to fund accounts.
   ```bash
   ./mvnw spring-boot:run "-Dspring-boot.run.arguments=clients create --name=acme --scopes=read,write,admin"
   ```
2. With the app running, open two USD accounts (run this twice), and read one back:
   ```bash
   curl -X POST http://localhost:8080/v1/accounts -H "Authorization: Bearer $API_KEY" -H "Content-Type: application/json" -d '{"currency":"USD"}'
   ```
   ```bash
   curl http://localhost:8080/v1/accounts/$ACCOUNT_ID -H "Authorization: Bearer $API_KEY"
   ```
   ```bash
   curl "http://localhost:8080/v1/accounts/$ACCOUNT_ID/entries?limit=50" -H "Authorization: Bearer $API_KEY"
   ```
3. Fund the first account with $1,000.00. Until real bank payments exist, this stands in for a deposit, and needs an `admin` key.
   ```bash
   curl -X POST http://localhost:8080/v1/fundings -H "Authorization: Bearer $API_KEY" -H "Idempotency-Key: fund-1" -H "Content-Type: application/json" -d "{\"accountId\":\"$ACCOUNT_ID\",\"amount\":{\"amount\":100000,\"currency\":\"USD\"},\"externalReference\":\"BANK-REF-1\"}"
   ```
4. Move $250.00 to the second account, then read the transfer back:
   ```bash
   curl -X POST http://localhost:8080/v1/transfers -H "Authorization: Bearer $API_KEY" -H "Idempotency-Key: transfer-1" -H "Content-Type: application/json" -d "{\"sourceAccountId\":\"$ACCOUNT_ID\",\"destinationAccountId\":\"$OTHER_ACCOUNT_ID\",\"amount\":{\"amount\":25000,\"currency\":\"USD\"},\"description\":\"rent\"}"
   ```
   ```bash
   curl http://localhost:8080/v1/transfers/$TRANSFER_ID -H "Authorization: Bearer $API_KEY"
   ```

**Conventions:**
- **Amounts** are integer minor units plus a currency: `{"amount": 1050, "currency": "USD"}` is $10.50. Anything else, such as `10.5` or `"1050"`, is rejected, and so are fields the API doesn't define.
- **Every money-moving POST needs an `Idempotency-Key` header.** Retrying with the same key and body returns the original response (with `Idempotent-Replayed: true`) for at least 24 hours, and never moves money twice. The same key with a different body is a 422. A 409 `request-in-progress` means the first attempt hasn't finished: retry after `Retry-After` with the same key and body.
- **Errors** use RFC 9457 Problem Details, with one `type` per kind of error ([catalogue](docs/design.md#errors)) and a `requestId` that matches the `X-Request-Id` header.
- **A 503 `account-busy` means "try again later":** other requests held the account for too long. Nothing moved, so retry after the `Retry-After` delay with the same `Idempotency-Key`.
- **Another client's account** returns 404, exactly like one that doesn't exist.

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
