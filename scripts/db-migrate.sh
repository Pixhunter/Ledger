#!/usr/bin/env bash
# Applies db/migration/*.sql WITHOUT starting the application.
#
# Needed to break a bootstrap cycle: the app cannot compile until the jOOQ
# classes exist, the classes are generated from a live schema, and the schema
# is created by migrations the app would otherwise run at startup. Tooling
# therefore needs its own path to the database.
#
# Uses the Flyway container directly, so nothing here depends on the Kotlin
# code compiling. Normal runs still migrate in-process at startup; this is a
# no-op when the schema is already current.
#
# Usage: ./scripts/db-migrate.sh
set -euo pipefail

cd "$(dirname "$0")/.."

FLYWAY_IMAGE="flyway/flyway:11-alpine"
CONTAINER="${PG_CONTAINER:-ledger-postgres}"
DB_NAME="${DB_NAME:-ledger}"
DB_USER="${DB_USER:-ledger}"
DB_PASSWORD="${DB_PASSWORD:-ledger}"

docker exec "$CONTAINER" pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1 || {
  echo "Postgres container '$CONTAINER' is not running. Start it with: docker compose up -d"
  exit 1
}

NETWORK=$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$CONTAINER" | head -1)

echo "==> applying db/migration"
docker run --rm \
  --network "$NETWORK" \
  -v "$PWD/db/migration:/flyway/sql:ro" \
  "$FLYWAY_IMAGE" \
  -url="jdbc:postgresql://${CONTAINER}:5432/${DB_NAME}" \
  -user="$DB_USER" -password="$DB_PASSWORD" -connectRetries=10 \
  migrate

echo
echo "==> tables"
docker exec -i "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -At -c \
  "SELECT table_schema || '.' || table_name
     FROM information_schema.tables
    WHERE table_type = 'BASE TABLE'
      AND table_schema NOT IN ('pg_catalog','information_schema')
    ORDER BY 1;" | sed 's/^/    /'
