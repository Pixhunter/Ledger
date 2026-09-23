# Ledger

## Requirements

Based on our conversation - the company works in different countries.
So it's better to include:
- country
- currency
- taxes

## Stack
- kotlin
- Postgres
- Docker


Service description
- Capture each payment atomically before returning from the API.
- Make requests idempotent so the same event is not posted twice.
- Keep the ledger balanced and append-only.


## Diagrams
### Payment process

```mermaid
sequenceDiagram
    autonumber
    participant PSP
    participant L as Ledger service
    participant DB as Database
    participant R as Review + alerts

    PSP->>L: POST /v1/payments/capture
    L->>L: verify signature, schema
    alt invalid
        L-->>PSP: 401 / 400 (nothing saved)
    end

    alt success = false
        L->>DB: insert payment FAILED (idempotent)
        L-->>PSP: 200 (no money posted)
    end

    L->>L: vote tax country (billing, card, IP)
    L->>L: rate from config, compute tax + fee + merchantNet

    L->>DB: ONE statement, ONE round trip:<br/>insert payment if pspReference is new<br/>status = POSTED if merchant exists, else HELD<br/>insert ledger entries<br/>return stored row
    DB-->>L: payment (new or existing)

    alt duplicate
        L-->>PSP: 200 stored answer
    else HELD (unknown merchant / tax unresolved)
        L--)R: alert (async, fire and forget)
        L-->>PSP: 200 received
    else POSTED
        L-->>PSP: 200 received
    end
```



----

## Running locally

### Prerequisites

- JDK 21
- Docker

### 1. Start Docker

### 1. Start Postgres and apply migrations

```bash
cd ~/IdeaProjects/Ledger
docker compose up -d          # or: docker-compose up -d
```

This starts Postgres on **localhost:3333** and runs a one-shot Flyway container
that applies everything in `db/migration/`. Flyway records applied scripts in
`flyway_schema_history`, so re-running is a no-op.

Verify:

```bash
docker compose ps                  # postgres up, flyway exited 0
docker compose logs flyway         # "Successfully applied N migrations"
psql postgresql://ledger:ledger@localhost:3333/ledger -c '\dt'
```

### 3. Run the service

```bash
./gradlew build
./gradlew run
```

### Stopping

```bash
docker compose down        # keeps data
docker compose down -v     # wipes the volume; next up re-migrates from scratch
```

### Connection details

| setting  | value                                       |
|----------|---------------------------------------------|
| JDBC URL | `jdbc:postgresql://localhost:3333/ledger`   |
| user     | `ledger`                                    |
| password | `ledger`                                    |

Overridable via `DB_URL`, `DB_USER`, `DB_PASSWORD`, `DB_POOL_SIZE`.


### Drop data and restart Postgres
```bash
chmod +x scripts/db-reset.sh
./scripts/db-reset.sh
```
----

# What the service does

Terse map of the pieces that exist today.

## Business logic

**Merchant of Record.** The MoR is the legal seller. The customer's money lands
in the MoR's account, never the merchant's. From one captured payment the MoR
owes three parties:

| owed to | amount |
|---|---|
| tax authority of the **customer's** country | tax portion |
| itself | its fee |
| the merchant | whatever is left |

Tax jurisdiction follows the buyer, not the merchant. One merchant selling into
30 countries produces 30 separate tax liabilities.

**Money is booked in the currency it arrived in.** No conversion at capture:
tax authorities want their own currency anyway, and converting early loses
precision. FX happens at payout, which is out of scope here.

**A capture is recorded synchronously.** The request is validated, split into
tax, fee and merchant amounts, and appended to the ledger before the API returns.
All entries for one capture are committed atomically and are idempotent on the
request ID.

```
POST /v1/payment/capture
        │
        ▼
 validate → calculate split → append balanced entries → 200 OK
```

The previous state-machine and job-runner implementation is preserved under
`shelved/state-machine-job-runner/`, outside the active build.

Postgres runs in Docker. Schema changes are **migrations only** — never edits to
a database by hand.

- `db/migration/*.sql` — the migrations, applied in order by Flyway
- `db/schema.sql` — generated dump of the resulting schema
- `db/schema.yaml` — generated flat listing, readable in a diff

The last two are rebuilt from the migrations, never hand-edited. Concatenating
migration files would be a lie as soon as a later one alters what an earlier one
created, so they are produced by actually applying every migration to a
throwaway Postgres and dumping what came out.

## Data access

The ledger repository uses jOOQ transactions with explicit SQL. jOOQ is blocking
JDBC, so repository calls hop to `Dispatchers.IO`, keeping Ktor's request threads
free.

## API

Ktor on port **8081**, contract in `api/openapi.yaml` (+ `api/definitions.yaml`).

| method | path | does |
|---|---|---|
| POST | `/v1/payment/capture` | record a captured payment |
| GET | `/health` | liveness |

Ids are UUIDs. Amounts are integer minor units — never floating point. Tax rates
are basis points, because integer percent cannot express 8.875%.

----

# Running it from scratch

Start the database before starting the application.

### 0. Prerequisites

- JDK 21
- Docker running (Colima or Docker Desktop)

```bash
colima start                 # Colima only
docker info | head -5        # must NOT say "Cannot connect to the Docker daemon"
```

### 1. Start Postgres and apply migrations

```bash
docker compose up -d         # or: docker-compose up -d
```

Postgres on **localhost:3333**; a one-shot Flyway container applies
`db/migration/`. Re-running is a no-op — Flyway records what it applied.

```bash
docker compose ps            # postgres up, flyway exited 0
docker compose logs flyway   # "Successfully applied N migrations"
```

### 2. Refresh the generated schema files

```bash
chmod +x scripts/*.sh
./scripts/db-schema.sh
```

Writes `db/schema.sql` and `db/schema.yaml`. Uses a throwaway container — your
running database is untouched.

### 3. Generate the jOOQ classes

```bash
./scripts/jooq-generate.sh
```

Reads `db/schema.sql`, generates into `src/generated/jooq`. Also a throwaway
container, on port 55432, so the dev database keeps running.

Steps 2 and 3 are only needed after a migration changes. Otherwise skip to 4.

### 4. Build and run

```bash
./gradlew build
./gradlew run
```

### 5. Try it

Once the app is running, everything is reachable on the port from
`config/application.yaml` (8081 by default):

| what | link |
|---|---|
| **Swagger UI** | <http://localhost:8081/swagger> |
| OpenAPI spec | <http://localhost:8081/openapi.yaml> |
| Schema definitions | <http://localhost:8081/definitions.yaml> |
| Health | <http://localhost:8081/health> |

Use the `/swagger` link rather than opening `api/api.yaml` in the IDE preview.
The spec deliberately has no hardcoded `servers` entry - the app injects it from
config when serving `/openapi.yaml`, so the UI always points at wherever the app
is actually listening. An IDE preview serves the raw file from its own web
server (:63342) and "Try it out" would post there instead.

```bash
curl localhost:8081/health

curl -X POST localhost:8081/v1/payment/capture \
  -H 'Content-Type: application/json' \
  -d '{
    "requestId":  "0b7a2c14-8f3e-4d16-9c5a-2e7b41d0f9a3",
    "merchantId": "5f1d9e02-3ab7-4c88-b0e5-6d427fa1c93b",
    "customerId": "c93e7a5b-1204-4f6d-8e91-7b3ad5c02e48",
    "amount":     10000,
    "currency":   "EUR"
  }'
```

Send it twice: the second call returns the existing state and creates no second
task.

### Resetting

```bash
./scripts/db-reset.sh        # drops the volume, re-applies every migration
```

Needed while a migration file is still being edited — Flyway stores a checksum
per applied script, so changing `V1` after it ran fails validation instead of
silently re-running. Once a migration is pushed, don't edit it: add the next one.

### Troubleshooting

| symptom | cause |
|---|---|
| `Address already in use` on 8081 | something else holds the port — `lsof -i:8081` |
| response header says `server: IntelliJ IDEA` | IntelliJ's built-in server answered, not this app |
| `No SLF4J providers were found` | stale build — `./gradlew clean run` |
| no output from `logger.info` | Klogging needs its own sink; configured in `Main.kt` |
| Flyway validation error | a migration file changed after being applied — see Resetting |

