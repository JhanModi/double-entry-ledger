#!/bin/sh
# Creates the application's database roles (ADR-0015). The Postgres image runs this once, when the data volume is
# first created, as the owner role:
#
#   ledger_app     group role with no login; migrations grant the app's privileges to it
#   $APP_DB_USER   the login the application connects as; a member of ledger_app
#
# Roles and passwords belong to the environment, not to migrations, which are committed to git.
set -eu

# Values are passed as psql variables (:"name" quotes an identifier, :'name' a literal), so a password containing
# quotes can't break or inject into the SQL.
psql --set ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set app_user="$APP_DB_USER" --set app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE ledger_app NOLOGIN;
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password' IN ROLE ledger_app;
SQL
