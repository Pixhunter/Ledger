#!/usr/bin/env bash
# Regenerates request and response DTOs from api/api.yaml into src/generated/api.
#
# The spec is the single source of truth. Hand-written DTOs drift from it
# silently - a renamed field only fails at runtime, against a real PSP.
#
# Generated sources are committed. Run this after changing the API schemas,
# then commit the refreshed output.
set -euo pipefail

cd "$(dirname "$0")/.."

OUT_DIR="src/generated/api"

[ -f api/api.yaml ] || { echo "api/api.yaml not found"; exit 1; }

echo "==> generating from api/api.yaml"
./gradlew --console=plain apiCodegen

generated=$(find "$OUT_DIR" -name '*.kt' 2>/dev/null | wc -l | tr -d ' ')
if [ "$generated" -eq 0 ]; then
  echo "No classes generated - check the schemas in api/definitions.yaml."
  exit 1
fi

echo
find "$OUT_DIR" -name '*.kt' | sed 's/^/    /'
echo
echo "Generated $generated files. Commit ${OUT_DIR}."
