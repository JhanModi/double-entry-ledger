-- The application's database privileges (ADR-0015).
--
-- The environment creates the roles: docker/postgres/initdb locally, src/test/resources/db/testcontainers-roles.sql in
-- tests, and infrastructure in production. Migrations never create roles or contain passwords. They only grant
-- privileges to the ledger_app group role, which the app's login (ledger_service) is a member of.

GRANT SELECT ON currencies TO ledger_app;

GRANT SELECT, INSERT ON accounts TO ledger_app;
GRANT UPDATE (posted_balance, held_balance, status) ON accounts TO ledger_app;

GRANT SELECT, INSERT ON ledger_transactions TO ledger_app;
GRANT SELECT, INSERT ON entries TO ledger_app;
