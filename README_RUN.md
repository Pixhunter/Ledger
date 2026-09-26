# Running the MoR ledger

## Prerequisites

- JDK 21
- Docker running (Colima or Docker Desktop)

```bash
colima start          # Colima only
docker info | head -3 # must not say "Cannot connect to the Docker daemon"
chmod +x scripts/*.sh
```

## 1. Postgres

```bash
./scripts/db-reset.sh     # empty database on localhost:3333
./scripts/db-migrate.sh   # applies db/migration/*.sql
```

`docker compose up -d` starts the same container without migrating.
Compose runs Postgres only - Flyway is a script, or runs in-process at startup.

| setting  | value                                     |
|----------|-------------------------------------------|
| JDBC URL | `jdbc:postgresql://localhost:3333/ledger` |
| user     | `ledger`                                  |
| password | `ledger`                                  |

Override with `DB_URL`, `DB_USER`, `DB_PASSWORD`, `DB_POOL_SIZE`.

## 2. Generated sources

Both are committed, so a clean clone builds with no database.
Re-run only after changing a migration or the API spec.

```bash
./scripts/jooq-generate.sh   # db/migration -> src/generated/jooq (needs step 1)
./scripts/api-generate.sh    # api/definitions.yaml -> src/generated/api
./scripts/db-schema.sh       # refreshes db/schema.sql
```

## 3. Run

```bash
./gradlew run
```

## 4. Swagger

With the app running:

| what         | link                                    |
|--------------|-----------------------------------------|
| Swagger UI   | <http://localhost:8081/swagger>         |
| OpenAPI spec | <http://localhost:8081/openapi.yaml>    |
| Health       | <http://localhost:8081/health>          |

Use `/swagger`, not the IDE's YAML preview: the app injects the `servers` entry
from config, so "Try it out" posts to the running app instead of the IDE's own
web server on :63342.

```bash
SECRET=dev-psp-secret-change-me
BODY='{"pspReference":"psp-1","merchantId":"5f1d9e02-3ab7-4c88-b0e5-6d427fa1c93b","amount":12100,"currency":"EUR","success":true,"billingAddress":{"country":"ES"},"cardIssuingCountry":"ES","paymentTime":"2026-09-22T10:15:30Z"}'
SIG=$(printf %s "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $2}')

curl -X POST localhost:8081/v1/payments \
  -H 'Content-Type: application/json' \
  -H "X-Psp-Signature: $SIG" \
  -d "$BODY"

# the request below is the same body, unsigned - it returns 401
curl -X POST localhost:8081/v1/payments \
  -H 'Content-Type: application/json' \
  -d '{
    "pspReference": "psp-1",
    "merchantId":   "5f1d9e02-3ab7-4c88-b0e5-6d427fa1c93b",
    "amount":       12100,
    "currency":     "EUR",
    "billingAddress": { "country": "ES" },
    "cardIssuingCountry": "ES",
    "paymentTime": "2026-09-22T10:15:30Z"
  }'
```

Send it twice - the second call returns the stored answer and writes nothing.

## 5. Tests

```bash
./gradlew test --rerun
```

Unit tests need nothing. `PaymentRepositoryTest` starts its own Postgres with
Testcontainers and needs Docker; without it those tests report **SKIPPED**, so
check the output rather than trusting `BUILD SUCCESSFUL`.

On Colima the build points Testcontainers at `~/.colima/default/docker.sock`
and pins the Docker API version - see `tasks.test` in `build.gradle.kts`.

## Troubleshooting

| symptom | cause |
|---|---|
| Flyway checksum mismatch | a migration changed after it was applied - `./scripts/db-reset.sh` |
| `Could not find a valid Docker environment` | Docker is not running |
| database tests SKIPPED | same, or the socket path is not in `tasks.test` |
| `server: IntelliJ IDEA` in a response | IntelliJ's built-in server answered, not this app |
| `Address already in use` on 8081 | `lsof -i:8081` |
