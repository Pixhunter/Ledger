#!/usr/bin/env bash
# One command for the whole generated state of the service.
#
#   1. api-generate    api/api.yaml        -> src/generated/api
#   2. db-reset        drop the volume, start an empty Postgres
#   3. db-migrate      db/migration/*.sql  -> live schema
#   4. jooq-generate   live schema         -> src/generated/jooq
#   5. db-schema       live schema         -> db/schema.sql
#
# Order matters: jOOQ generates from a LIVE database, so the schema has to
# exist first, and migrations cannot run through the app because the app
# cannot compile until the jOOQ classes exist.
#
# Destructive: step 2 deletes the dev database volume. Dev data only.
#
# Usage:
#   ./scripts/rebuild-all.sh            # everything
#   ./scripts/rebuild-all.sh --keep-db  # skip the reset, keep existing data
#   ./scripts/rebuild-all.sh --test     # everything, then ./gradlew test --rerun
set -euo pipefail

cd "$(dirname "$0")/.."

KEEP_DB=false
RUN_TESTS=false
for arg in "$@"; do
  case "$arg" in
    --keep-db) KEEP_DB=true ;;
    --test) RUN_TESTS=true ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

STEP=0
step() { STEP=$((STEP + 1)); printf '\n\033[1m[%d/%d] %s\033[0m\n' "$STEP" "$TOTAL" "$1"; }

TOTAL=5
$KEEP_DB && TOTAL=4
$RUN_TESTS && TOTAL=$((TOTAL + 1))

START=$SECONDS

docker info >/dev/null 2>&1 || {
  echo "Docker is not running. Start Docker Desktop, or 'colima start'." >&2
  exit 1
}

chmod +x scripts/*.sh

step "API DTOs from api/api.yaml"
./scripts/api-generate.sh

if $KEEP_DB; then
  echo "(skipping database reset)"
else
  step "empty Postgres"
  ./scripts/db-reset.sh
fi

step "migrations"
./scripts/db-migrate.sh

step "jOOQ classes from the live schema"
./scripts/jooq-generate.sh

step "db/schema.sql"
./scripts/db-schema.sh

if $RUN_TESTS; then
  step "tests"
  ./gradlew --console=plain test --rerun
fi

printf '\n\033[1mDone in %ds.\033[0m\n' "$((SECONDS - START))"
echo "  src/generated/api   $(find src/generated/api -name '*.kt' 2>/dev/null | wc -l | tr -d ' ') files"
echo "  src/generated/jooq  $(find src/generated/jooq -name '*.kt' 2>/dev/null | wc -l | tr -d ' ') files"
echo
echo "Next: ./gradlew run"
