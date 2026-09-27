# Merchant of Record ledger     ~(=^・ω・^)ﾉ

For local run -> **[Run locally](README_RUN.md)**  

A Kotlin/PostgreSQL ledger for a Merchant of Record (MoR). It records PSP
payment and refund notifications, then derives tax liabilities and merchant
balances.

The PSP already moved the money before calling this service. The ledger records
that fact; checkout, fraud and refund approval happen earlier.

**Why a rolled-own ledger on PostgreSQL, not Formance or TigerBeetle:** the data
is relational - payments, refunds, merchants, quarantined events - so one
database keeps a payment, its ledger entries and its idempotency key in a single
transaction, and leaves room to grow the business rules and reporting around
them. A dedicated posting engine would be a second store to reconcile with.

![Service architecture](docs/images/service-architecture.svg)

### Capture, end to end

```mermaid
sequenceDiagram
    participant PSP as PSP webhook
    participant API as Ledger API
    participant SVC as Payment logic
    participant DB as PostgreSQL
    participant JOB as Scheduled jobs
    participant EXT as Bank / tax authority

    rect rgb(234, 242, 253)
    Note over PSP,EXT: SYNCHRONOUS - inside the PSP request
    PSP->>API: POST /v1/payment/capture, HMAC signed
    API->>API: verify signature. Invalid 401, nothing written
    API->>API: parse and validate. Malformed 400, nothing written
    API->>SVC: createPayment(request)
    SVC->>SVC: tax country vote from billing, card and IP
    SVC->>SVC: freeze the rate, check VAT ID for reverse charge
    SVC->>SVC: split gross into tax, MoR fee, merchant net
    SVC->>DB: insert payment and ledger entries
    rect rgb(224, 245, 236)
    Note over DB: ONE TRANSACTION. All of it, or none of it
    DB->>DB: INSERT payment, unique pspReference
    DB->>DB: INSERT ledger_transaction CAPTURE
    DB->>DB: INSERT PSP +gross, TAX -tax, REVENUE -fee
    DB->>DB: INSERT MERCHANT -net, or HELD -net if unresolved
    DB->>DB: INSERT payment_hold and processing_error
    end
    DB-->>SVC: COMMIT
    SVC-->>API: Recorded(status)
    API-->>PSP: 200 SUCCESS, only after commit
    end

    rect rgb(253, 244, 227)
    Note over PSP,EXT: ASYNCHRONOUS - scheduled, Europe/London
    Note over JOB: 01:00 PayoutCalculationJob
    JOB->>DB: snapshot merchant_daily_balance
    JOB->>DB: settle MERCHANT entries, payout COMPUTED
    Note over JOB: 01:30 DisbursementJob PAYOUT
    JOB->>DB: claim COMPUTED with FOR UPDATE SKIP LOCKED
    JOB->>EXT: send payout, reference is the idempotency key
    EXT-->>JOB: accepted
    JOB->>DB: payout SENT
    Note over JOB: 02:00 on the 5th, TaxRemittanceCalculationJob
    JOB->>DB: settle TAX entries dated before the period end
    Note over JOB: 02:30 TaxBalanceMonitorJob
    JOB->>DB: snapshot tax_daily_balance
    Note over JOB: 03:00 DisbursementJob TAX
    JOB->>EXT: file and pay the remittance
    JOB->>DB: remittance SENT
    end
```

### Refund, end to end

```mermaid
sequenceDiagram
    participant PSP as PSP webhook
    participant API as Ledger API
    participant SVC as Refund logic
    participant DB as PostgreSQL
    participant JOB as Scheduled jobs
    participant EXT as Bank / tax authority

    rect rgb(234, 242, 253)
    Note over PSP,EXT: SYNCHRONOUS - inside the PSP request
    PSP->>API: POST /v1/payment/refund, HMAC signed
    API->>API: verify signature. Invalid 401, nothing written
    API->>API: parse and validate. Malformed 400, nothing written
    API->>SVC: createRefund(request)
    SVC->>DB: find payment by pspReference
    DB-->>SVC: payment, or none which answers 404 and the PSP retries
    SVC->>SVC: refundedAt must be at or after paymentTime, and within 2 days
    SVC->>SVC: outside the window is INVALID_DATE, quarantined, 200
    SVC->>DB: insert refund and ledger entries
    rect rgb(224, 245, 236)
    Note over DB: ONE TRANSACTION. All of it, or none of it
    DB->>DB: SELECT payment FOR UPDATE, serialises concurrent refunds
    DB->>DB: SUM previous refunds for the cumulative tax and fee share
    DB->>DB: INSERT refund, unique refundReference
    DB->>DB: INSERT ledger_transaction REFUND
    DB->>DB: INSERT PSP -amount, TAX +tax, MERCHANT +net
    DB->>DB: excess goes to SUSPENSE with an OVER_REFUND error
    end
    DB-->>SVC: COMMIT
    SVC-->>API: Recorded(status)
    API-->>PSP: 200 SUCCESS, only after commit
    end

    rect rgb(253, 244, 227)
    Note over PSP,EXT: ASYNCHRONOUS - scheduled, Europe/London
    Note over JOB: 01:00 PayoutCalculationJob
    JOB->>DB: merchant balance is already lower, the refund reduced it
    JOB->>DB: non-positive balance is carried forward, no payout
    JOB->>DB: 14 consecutive negative days raise a processing_error
    Note over JOB: 01:30 DisbursementJob PAYOUT, positive balances only
    Note over JOB: 02:00 on the 5th, TaxRemittanceCalculationJob
    JOB->>DB: refunds inside the period lower the filed amount
    Note over JOB: 02:30 TaxBalanceMonitorJob
    JOB->>DB: a refund after filing leaves the tax balance negative
    JOB->>DB: negative for 3 days raises a processing_error
    Note over JOB: 03:00 DisbursementJob TAX
    JOB->>EXT: pay the net remittance
    end
```

## What is implemented

| Requirement      | Implementation                                                                 |
|------------------|--------------------------------------------------------------------------------|
| Capture payment  | Authenticated, atomic, idempotent posting                                      |
| Refund payment   | Full, partial, repeated and over-refunds                                       |
| Tax balance      | Current or period report per country                                           |
| Merchant balance | Live or daily-snapshot report with keyset pagination                           |
| Settlement       | Daily merchant payouts and monthly tax remittance using a mock transfer client |

One currency (EUR), one global MoR fee, and digital goods/services are assumed.

## Payments

`POST /v1/payment/capture`

| Event                          | Result                                           |
|--------------------------------|--------------------------------------------------|
| Successful capture             | Split into tax, MoR revenue and merchant balance |
| Unknown/suspended merchant     | Record payment; hold merchant money              |
| Tax cannot be resolved         | Record payment; hold money for review            |
| Exact retry                    | Return `200`; write nothing                      |
| Same reference, different data | Keep first event; save `IDEMPOTENCY_CONFLICT`    |
| `success=false`                | Return `200`; write nothing                      |
| Bad signature/body             | Return `401`/`400`; write nothing                |
| Internal error                 | Return `500`; write nothing                      |

Tax country uses billing, card and IP country evidence. Two matching signals
win; billing is the fallback. The selected rate and evidence are frozen on the
payment. Valid cross-border VAT ID format enables reverse charge; live VIES
validation is not implemented.

Money is exact: API amounts have at most two EUR decimal places, Kotlin uses
`BigDecimal`, PostgreSQL uses `numeric(19,4)`, and calculations use
`HALF_EVEN` rounding.

```text
tax         = gross × rate / (100% + rate)
fee         = (gross - tax) × configured fee
merchantNet = gross - tax - fee
```

## Refunds

`POST /v1/payment/refund`

| Event                          | Result                                                           |
|--------------------------------|------------------------------------------------------------------|
| Missing payment                | Return `404`; retry after capture arrives                        |
| Partial/full refund            | Record refund and balanced ledger entries                        |
| Multiple concurrent refunds    | Serialize on the payment row                                     |
| Exact retry                    | Return `200`; write nothing                                      |
| Same reference, different data | Keep first event; save `IDEMPOTENCY_CONFLICT`                    |
| Total exceeds payment          | Record full refund; put excess in `SUSPENSE`; save `OVER_REFUND` |
| Invalid refund date            | Save error; return `200`; do not book refund                     |
| Internal error                 | Return `500`; write nothing                                      |

Tax reversal uses cumulative allocation. Small refunds may initially reverse no
tax, later refunds catch up, and a full refund reverses exactly the original
tax despite cent rounding.

The current policy keeps MoR revenue on every refund. The merchant funds the
refund after tax reversal. `fee_returned` is frozen on each refund so a future
policy can safely change this behavior.

## Ledger and balances

Every money event creates one transaction with balanced entries:

| Account         | Meaning                       |
|-----------------|-------------------------------|
| `PSP`           | Cash received or returned     |
| `TAX:<country>` | Tax liability                 |
| `REVENUE`       | MoR fee                       |
| `MERCHANT:<id>` | Payable merchant money        |
| `HELD:<id>`     | Recorded but not payable      |
| `SUSPENSE:<id>` | Unresolved over-refund excess |

Accounting amounts are append-only. Payout and tax jobs only attach settlement
links to the exact source entries they consume.

Reports:

- `GET /v1/balances/tax` — current or period liability for one country.
- `GET /v1/balances/merchants` — available and held balance, live or by daily
  snapshot; default page 100, maximum 500.

Each report request uses one database snapshot. `occurred_at` is the PSP event
time, so late webhooks remain in the correct financial period.
Settlement transfer entries are excluded from these reports, so running a
payout or tax-remittance job does not erase the calculated business balance.

## Database schema

```mermaid
erDiagram
    MERCHANT {
        uuid id PK "NOT NULL"
        text name "NOT NULL"
        text currency "NOT NULL"
        int fee_rate_bps "NOT NULL"
        smallint tax_category "NOT NULL"
        smallint status "NOT NULL"
        timestamptz created_at "NOT NULL"
    }

    MERCHANT_PAYMENT_DETAILS {
        uuid merchant_id PK, FK "NOT NULL"
        text psp_account_id "NOT NULL"
        text account_holder "NOT NULL"
        text iban "NULL"
        text bic "NULL"
        text account_number "NULL"
        text routing_code "NULL"
        text bank_country "NOT NULL"
        jsonb address "NOT NULL"
        timestamptz updated_at "NOT NULL"
    }

    TAX_RATE {
        text country PK "NOT NULL"
        smallint category PK "NOT NULL"
        date valid_from PK "NOT NULL"
        int rate_bps "NOT NULL"
    }

    PAYMENT {
        uuid id PK "NOT NULL"
        text psp_reference UK "NOT NULL"
        uuid merchant_id FK "NULL"
        numeric_19_4 gross "NOT NULL"
        numeric_19_4 tax "NOT NULL"
        numeric_19_4 fee "NOT NULL"
        numeric_19_4 merchant_net "NOT NULL"
        text currency "NOT NULL"
        text tax_country "NULL"
        smallint tax_category "NULL"
        int tax_rate_bps "NULL"
        boolean reverse_charge "NOT NULL"
        jsonb evidence "NOT NULL"
        smallint status "NOT NULL"
        timestamptz payment_time "NOT NULL"
        timestamptz created_at "NOT NULL"
    }

    PAYMENT_HOLD {
        uuid payment_id PK, FK "NOT NULL"
        smallint reason PK "NOT NULL"
        timestamptz created_at "NOT NULL"
        timestamptz resolved_at "NULL"
    }

    REFUND {
        uuid id PK "NOT NULL"
        text refund_reference UK "NOT NULL"
        uuid payment_id FK "NOT NULL"
        uuid ledger_transaction_id FK, UK "NOT NULL"
        numeric_19_4 amount "NOT NULL"
        text currency "NOT NULL"
        smallint reason "NOT NULL"
        boolean fee_returned "NOT NULL"
        timestamptz refunded_at "NOT NULL"
        timestamptz created_at "NOT NULL"
    }

    LEDGER_TRANSACTION {
        uuid id PK "NOT NULL"
        smallint type "NOT NULL"
        uuid payment_id FK "NULL"
        timestamptz created_at "NOT NULL"
    }

    LEDGER_ENTRY {
        bigint_identity id PK "NOT NULL"
        uuid transaction_id FK "NOT NULL"
        smallint purpose "NOT NULL"
        text purpose_key "NULL"
        numeric_19_4 amount "NOT NULL"
        text currency "NOT NULL"
        timestamptz occurred_at "NOT NULL"
        uuid settled_by_transaction_id FK "NULL"
    }

    PAYOUT {
        uuid merchant_id PK, FK "NOT NULL"
        date payout_date PK "NOT NULL"
        numeric_19_4 amount "NOT NULL"
        text currency "NOT NULL"
        uuid ledger_transaction_id FK, UK "NOT NULL"
        smallint status "NOT NULL"
        text psp_reference "NULL"
        timestamptz claimed_at "NULL"
        timestamptz created_at "NOT NULL"
    }

    MERCHANT_DAILY_BALANCE {
        uuid merchant_id PK, FK "NOT NULL"
        date balance_date PK "NOT NULL"
        numeric_19_4 balance "NOT NULL"
        text currency "NOT NULL"
        timestamptz created_at "NOT NULL"
    }

    TAX_REMITTANCE {
        text country PK "NOT NULL"
        date period_start PK "NOT NULL"
        numeric_19_4 amount "NOT NULL"
        text currency "NOT NULL"
        uuid ledger_transaction_id FK, UK "NOT NULL"
        smallint status "NOT NULL"
        text reference "NULL"
        timestamptz claimed_at "NULL"
        timestamptz created_at "NOT NULL"
    }

    TAX_DAILY_BALANCE {
        text country PK "NOT NULL"
        date balance_date PK "NOT NULL"
        numeric_19_4 balance "NOT NULL"
        text currency "NOT NULL"
        timestamptz created_at "NOT NULL"
    }

    PROCESSING_ERROR {
        uuid id PK "NOT NULL"
        smallint event_type UK "NOT NULL; composite UK"
        text external_reference UK "NOT NULL; composite UK"
        jsonb payload "NOT NULL"
        smallint error_code UK "NOT NULL; composite UK"
        text error_detail "NOT NULL"
        timestamptz created_at "NOT NULL"
    }

    MERCHANT ||--|| MERCHANT_PAYMENT_DETAILS : has
    MERCHANT o|--o{ PAYMENT : receives
    MERCHANT ||--o{ MERCHANT_DAILY_BALANCE : snapshots
    MERCHANT ||--o{ PAYOUT : receives
    PAYMENT ||--o{ PAYMENT_HOLD : has
    PAYMENT ||--o{ REFUND : refunded_by
    PAYMENT o|--o{ LEDGER_TRANSACTION : owns
    LEDGER_TRANSACTION ||--|{ LEDGER_ENTRY : contains
    LEDGER_TRANSACTION o|--o{ LEDGER_ENTRY : settles
    LEDGER_TRANSACTION ||--o| REFUND : records
    LEDGER_TRANSACTION ||--o| PAYOUT : records
    LEDGER_TRANSACTION ||--o| TAX_REMITTANCE : records
```

Colors group merchant data, tax data, balances and settlement, incoming money
events, and operational errors in the retained
[SVG version](docs/images/database-schema.svg). The Mermaid diagram above is
the README-native schema.

## Settlement

All times use `Europe/London`.

|               Time | Job                                                           |
|-------------------:|---------------------------------------------------------------|
|        Daily 01:00 | Snapshot balances and calculate previous-day merchant payouts |
|        Daily 01:30 | Send computed payouts                                         |
| Monthly, 5th 02:00 | Calculate previous-month tax remittances                      |
|        Daily 02:30 | Snapshot and monitor tax balances                             |
|        Daily 03:00 | Send computed tax remittances                                 |

Payout calculation reserves one `(merchant, date)` row, locks and settles only
eligible ledger entries, and leaves racing or late entries for the next run.
Non-positive and suspended merchant balances are not paid. Fourteen consecutive
negative days create an operational error.

The manual compute endpoints are repeatable while a batch is still `COMPUTED`:
newly arrived entries are appended to the existing payout or tax remittance.
A run with no new entries is a no-op; a batch already being sent is immutable.

Transfers use safe row claiming with `FOR UPDATE SKIP LOCKED`, a `PROCESSING`
state and stable receiver idempotency keys. The current transfer client always
accepts; no real money is sent.

## Reliability choices

- `200` only after database commit.
- Unique PSP/refund references prevent duplicate events.
- Changed duplicate payloads are preserved for review.
- Payment row locking protects cumulative refund allocation.
- Payout keys and source-entry locks protect concurrent job runs.
- Invalid but important money facts use durable `HELD`, `SUSPENSE` or
  `processing_error` records.
- PSP references are propagated through coroutine-aware logging context.

The write path is synchronous by design. A job runner is used for settlement,
not for API ingestion, keeping acknowledgement and durable posting in one
operation.

## Measured locally

Three one-minute mixed payment/refund runs used 32 workers, 10,000 preloaded
payments and 1,000 merchants. Median throughput was **738 requests/second**
with zero reported failures; median p95 was **191 ms**. Database invariants and
settlement results passed after each run.

This is laptop/Testcontainers evidence, not a production SLA. Rerun it with
`./gradlew mixedLoadTest` (needs Docker; not part of `test` or CI). See
[src/loadTest/README.md](src/loadTest/README.md) for the knobs.

## Current limits

- Runtime uses the global fee and `STANDARD` tax category; seeded merchant
  fee/category fields are not used for capture calculation.
- Tax and VAT rules are hardcoded Kotlin reference data; `tax_rate` is unused.
- EUR only.
- No hold/suspense resolution, chargebacks or failed-refund reversal.
- No real transfers, reconciliation or transfer confirmation.
- No distributed scheduler lock or missed-run recovery.
- Balance and development endpoints are unauthenticated.
- Merchant bank data is not application-encrypted.
