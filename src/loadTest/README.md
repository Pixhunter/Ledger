# Mixed payment/refund load test

This is an opt-in Kotlin test. It is not part of `test`, `check`, or CI and it
adds no dependency or external benchmark tool.

```bash
./gradlew mixedLoadTest
```

Defaults:

- 10,000 existing payments and 1,000 merchants;
- 32 concurrent workers;
- one minute of mixed traffic;
- 60% new payments, 20% unique refunds against one hot payment, 10% duplicate
  payments, and 10% duplicate refunds.

It checks idempotency, lost rows, double-entry balance, payment splits, tax,
retained revenue, merchant payouts, and tax remittance. Results are written to
`load-test-results/`, which is ignored by Git.

The preload uses PostgreSQL `generate_series`, so larger histories do not send
millions of setup requests through the API. One million existing payments is
available when a larger machine and a longer setup time are acceptable:

```bash
./gradlew mixedLoadTest -PseedPayments=1000000
```

Useful short diagnostic run:

```bash
./gradlew mixedLoadTest -PloadSeconds=10 -PseedPayments=1000 -PloadMerchants=100
```

The test reuses the repository's existing PostgreSQL Testcontainers dependency.
It does not require k6, Grafana, Docker Compose, or a separately installed
database. Docker must still be available for Testcontainers, exactly as for the
existing integration tests.
