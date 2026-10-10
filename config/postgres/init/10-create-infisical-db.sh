#!/bin/sh
# ---------------------------------------------------------------------------
# Create the "infisical" database on the bundled Keycloak Postgres so Keycloak
# and Infisical can SHARE one container (the wizard's "share Keycloak's Postgres"
# option). Postgres hosts many databases on one instance; Keycloak uses the
# "keycloak" DB (POSTGRES_DB), Infisical uses "infisical".
#
# The Postgres image runs this once, on first init (empty data dir), as
# POSTGRES_USER. Idempotent. Harmless when sharing isn't used — it just leaves
# an empty, unused "infisical" database.
# ---------------------------------------------------------------------------
set -e
if psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" -tc \
      "SELECT 1 FROM pg_database WHERE datname='infisical'" | grep -q 1; then
  echo "[postgres-init] database 'infisical' already present"
else
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" -c "CREATE DATABASE infisical"
  echo "[postgres-init] created database 'infisical'"
fi
