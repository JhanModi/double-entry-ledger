-- Test-only roles, mirroring docker/postgres/initdb/10-create-app-roles.sh (ADR-0015). Testcontainers runs this as
-- the container's owner right after the container starts. The password is deliberately fake: this database exists only
-- for the length of a test run.
CREATE ROLE ledger_app NOLOGIN;
CREATE ROLE ledger_service LOGIN PASSWORD 'test-only-not-a-secret' IN ROLE ledger_app;
