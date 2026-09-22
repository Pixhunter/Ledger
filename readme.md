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
- We need to handle each payment by api request and do not lost it -> we need to have state machine
- We need to make the requests idempotent and do not process same request twice
- At each state we can failed and need to retry until it will be success 
- 


## Diagrams
### Payment process
```mermaid
sequenceDiagram
  autonumber
  participant PSP
  participant API as Ledger API
  participant DB as DB
  participant M as Merchant Service 

  PSP->>API: POST /pay
  API->>API: validate input
  API->>DB: seen event_id before?
  DB-->>API: yes → return original result (200)
  API->>M: get merchant
  M-->>API: 404 → API returns 422 to PSP
  M-->>API: merchant valid (fee terms)
  API->>API: split: tax / fee / merchant net
  API->>DB: append 4 entries atomically (sum = 0)
  DB-->>API: committed
  API-->>PSP: 200 OK

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

**A capture is a task, not a synchronous calculation.** Resolving the merchant,
enriching with tax rules and writing the ledger touch different systems, so they
cannot share one database transaction. The request is persisted first and
processed afterwards.

## State machine

Every incoming payment becomes a row in `jobs.task` plus an append-only history
in `jobs.task_state`.

```
POST /v1/payment/capture
        │
        ▼
   persist task + first state  ──► 202 Accepted (returned immediately)
        │
        ▼
   CREATE → GET_MERCHANT → PAY_TAXES → REVENUE → DONE
                    │
                    └── exhausted retries ──► FAILURE
```

Why it looks like this:

- **Idempotent on `request_id`** — a unique constraint, not a SELECT-then-INSERT.
  Two concurrent replays of the same webhook cannot both win a race the database
  is arbitrating.
- **State persisted after every transition** — a worker that dies is replaced and
  the next one resumes from the last recorded state.
- **Retries per step** with backoff; a lease (`locked_until`) frees a row whose
  worker crashed.
- **Errors are data**, kept per state in `task_state.error_message` with an
  attempt count, so a failure is inspectable rather than only logged.
- **History is append-only.** Nothing is updated in place, so "how did this task
  get here?" is answerable after the fact.

## Persistence

| table | holds |
|---|---|
| `jobs.task` | the request: ids, amount, currency, open/closed, context |
| `jobs.task_state` | one row per state reached, with errors and attempts |

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

jOOQ, with **generated** table classes (`src/generated/jooq`) rather than SQL
strings. Renaming a column and regenerating breaks the build instead of breaking
a request in production.

Generated sources are committed, so a clean clone compiles with no Docker and no
database. The cost is remembering to regenerate after a migration.

jOOQ is blocking JDBC, so every repository call hops to `Dispatchers.IO` — in one
place, not at each call site — keeping Ktor's event loop free.

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

Order matters: the database exists before the code that is generated from it.

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
