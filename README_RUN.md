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

`src/generated` is **not committed** - it is in `.gitignore`. A clean clone
does not compile until these run, and jOOQ reads the live database, so step 1
has to come first. CI does the same thing on every push
(`.github/workflows/ci.yml`).

```bash
./scripts/jooq-generate.sh   # db/migration -> src/generated/jooq (needs step 1)
./scripts/api-generate.sh    # api/definitions.yaml -> src/generated/api
./scripts/db-schema.sh       # refreshes db/schema.sql (optional)
```

Re-run `jooq-generate.sh` after changing a migration and `api-generate.sh`
after changing the API spec. Gradle equivalents: `./gradlew jooqCodegen
apiCodegen`.

## 3. Run

```bash
./gradlew run
```

## 4. Merchants

10 merchants are inserted from `src/main/resources/merchants.json` at startup:
new ones are created, changed ones updated, matching ones left alone. Use one
of their ids as `merchantId`, or the payment is recorded as `HELD`
(`UNKNOWN_MERCHANT`).

```
1a1e7d6c-0000-4000-8000-000000000001   Aurora SaaS,  fee 5%
2b2e7d6c-0000-4000-8000-000000000002   Baltic Courses, fee 3%
ada07d6c-0000-4000-8000-000000000010   Juniper Books, SUSPENDED
```

Blank `mor.merchantSeedResource` to switch it off; in production merchants
come from the onboarding service.

## 5. Swagger

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
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
BODY='{"pspReference":"psp-1","merchantId":"1a1e7d6c-0000-4000-8000-000000000001","amount":121.00,"currency":"EUR","success":true,"billingAddress":{"country":"ES"},"cardIssuingCountry":"ES","paymentTime":"'"$NOW"'"}'
SIG=$(printf %s "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $2}')

curl -X POST localhost:8081/v1/payment/capture \
  -H 'Content-Type: application/json' \
  -H "X-Psp-Signature: $SIG" \
  -d "$BODY"

# same body, unsigned - returns 401 and stores nothing
curl -i -X POST localhost:8081/v1/payment/capture \
  -H 'Content-Type: application/json' \
  -d "$BODY"
```

Send the signed call twice - the second returns the stored answer and writes
nothing.

`amount` is euros, not minor units: `121.00` is EUR 121.00. `paymentTime` is
the tax point and must be within 7 days of now, so the example generates it.

Refund it, then read the two reports:

```bash
SECRET=dev-psp-secret-change-me
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
REFUND='{"refundReference":"ref-1","pspReference":"psp-1","amount":50.00,"currency":"EUR","success":true,"reason":"CUSTOMER_REQUEST","refundedAt":"'"$NOW"'"}'
RSIG=$(printf %s "$REFUND" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $2}')

curl -X POST localhost:8081/v1/payment/refund \
  -H 'Content-Type: application/json' \
  -H "X-Psp-Signature: $RSIG" \
  -d "$REFUND"

curl "localhost:8081/v1/balances/tax?country=ES"
curl "localhost:8081/v1/balances/merchants?limit=10"
```

The balances endpoints are finance reports, not PSP webhooks, so they take no
signature.

## 6. Tests

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
| `Unresolved reference: jooq` / `dto` at compile time | step 2 was not run in this clone |

## Continuous integration

GitHub Actions runs `.github/workflows/ci.yml` for every push and pull
request. It uses JDK 21, regenerates the API and jOOQ sources from a clean
checkout, and runs the complete Gradle test suite. No repository secrets are
needed.

To prevent a pull request from being merged while tests are failing, open the
repository on GitHub and go to **Settings -> Rules -> Rulesets -> New branch
ruleset**. Target the default branch, enable **Require status checks to pass**,
and select **CI / test**. Enable **Require a pull request before merging** too
if changes should always go through review rather than being pushed directly
to the default branch.
