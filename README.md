# double-entry-ledger

A double-entry ledger and payments API in Java and Spring Boot.

> **Status: early development.** This is a walking skeleton: the build, database migrations, a health check, integration tests against real Postgres, and CI. No ledger features exist yet. See the [roadmap](docs/roadmap.md).

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
3. Start the app. Flyway applies database migrations on startup.
   ```bash
   ./mvnw spring-boot:run
   ```
4. Check health:
   ```bash
   curl http://localhost:8080/actuator/health
   ```
   Expected: HTTP 200 with `"status":"UP"` in the body.

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
