# Run the MoR ledger

## Fastest: Docker Compose

Requirement: Docker Desktop or Colima.

The commands below use Docker's Compose plugin. If your installation provides
the standalone command, replace `docker compose` with `docker-compose`.

```bash
docker compose up --build
```

This builds the application image, starts PostgreSQL, applies migrations,
seeds merchants and starts the API on port `8081`.

Check it:

```bash
curl http://localhost:8081/health
# {"status":"UP"}
```

Run in the background and view logs:

```bash
docker compose up -d --build
docker compose logs -f app
```

Stop it:

```bash
docker compose down       # keep database data
docker compose down -v    # also delete database data
```

## Docker image

Build only the application image:

```bash
docker compose build app # image: ledger:local
docker-compose build app
docker-compose up -d
```

Run the built image with its database:

```bash
docker compose up -d
```

The image contains the application, API specifications, configuration and
Flyway migrations. PostgreSQL remains a separate container.

## Swagger

Open <http://localhost:8081/swagger>.

Use the dropdown at the top:

- **PSP API** — payment, refund and balance endpoints.
- **Dev API** — manually run payout and tax jobs.

For PSP endpoints, click **Authorize** and enter:

```text
very_secret_key
```

Local configuration allows this secret directly in `X-Psp-Signature` so
Swagger can send requests. Production must use a real HMAC signature and keep
this shortcut disabled.

Raw specifications:

- <http://localhost:8081/openapi.yaml>
- <http://localhost:8081/dev-api.yaml>

Swagger assets load from a CDN. The API and raw specifications still work
without those browser assets.

## Run from source

Requirements: JDK 21 and Docker.

```bash
docker compose up -d postgres
./gradlew run
```

Generated API and jOOQ sources are committed, so a fresh clone needs no manual
code-generation step.

## Tests

```bash
./gradlew test
```

Integration tests use Testcontainers and therefore require Docker. A missing
Docker environment is reported as skipped database tests; check the test
output, not only `BUILD SUCCESSFUL`.

## After schema or API changes

Normal users do not need this. Contributors regenerate committed sources with:

```bash
./scripts/rebuild-all.sh --test
```

This resets the local development database unless `--keep-db` is supplied.

## Useful configuration

| Variable                  | Default                                   |
|---------------------------|-------------------------------------------|
| `DB_URL`                  | `jdbc:postgresql://localhost:3333/ledger` |
| `DB_USER` / `DB_PASSWORD` | `ledger` / `ledger`                       |
| `SERVER_PORT`             | `8081`                                    |
| `PSP_SECRET`              | value from `config/application.yaml`      |
| `PSP_ALLOW_SECRET_HEADER` | `true` locally                            |

## Common problems

| Problem                       | Fix                                                 |
|-------------------------------|-----------------------------------------------------|
| Port `8081` or `3333` is busy | Stop the process using it or change the mapped port |
| Flyway checksum mismatch      | `docker compose down -v`, then start again          |
| Database tests are skipped    | Start Docker and rerun the tests                    |
| Swagger returns `401`         | Authorize with the local secret above               |
