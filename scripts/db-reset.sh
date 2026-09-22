#!/usr/bin/env bash
# Wipes Postgres. The application re-applies every migration on its next start.
#
# Use while you are still editing an existing migration file: Flyway stores a
# checksum per applied script, so editing V1 after it ran fails validation on
# the next boot. Once a migration is pushed anywhere shared, do not edit it -
# add the next one.
set -euo pipefail

cd "$(dirname "$0")/.."

compose() {
  if docker compose version >/dev/null 2>&1; then docker compose "$@"; else docker-compose "$@"; fi
}

echo "==> dropping volume"
compose down -v

echo "==> starting postgres"
compose up -d postgres

echo "==> waiting for healthcheck"
status=starting
for _ in $(seq 1 30); do
  status=$(docker inspect -f '{{.State.Health.Status}}' ledger-postgres 2>/dev/null || echo starting)
  [ "$status" = "healthy" ] && break
  sleep 1
done
[ "$status" = "healthy" ] || { echo "postgres did not become healthy"; compose logs postgres | tail -20; exit 1; }

echo
echo "Empty database ready. Start the app (./gradlew run) to apply migrations."
