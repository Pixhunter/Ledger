#!/usr/bin/env bash
# Regenerates typed jOOQ classes from the running dev database.
#
# jOOQ generates from a LIVE database, not from .sql text, so the schema has to
# exist first. ./scripts/rebuild-all.sh does the whole chain; run this alone
# only when the schema is already current.
#
# src/generated is gitignored, so a clean clone must run this.
set -euo pipefail

cd "$(dirname "$0")/.."

DB_URL="${DB_URL:-jdbc:postgresql://localhost:3333/ledger}"
DB_USER="${DB_USER:-ledger}"
DB_PASSWORD="${DB_PASSWORD:-ledger}"
OUT_DIR="src/generated/jooq"

echo "==> generating from $DB_URL"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

./gradlew --console=plain jooqCodegen \
  -Pjooq.url="$DB_URL" \
  -Pjooq.user="$DB_USER" \
  -Pjooq.password="$DB_PASSWORD"

echo
find "$OUT_DIR" -name '*.kt' | sed 's/^/    /'
echo
echo "Commit ${OUT_DIR} so the build needs no database."
