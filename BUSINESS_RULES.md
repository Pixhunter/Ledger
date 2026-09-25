# Business rules for MoR ledger service

This document explains the business behaviour of the Merchant of Record (MoR)
ledger service.

## Service architecture assumptions

- The ledger receives PSP notifications and must not lose valid money events.
- The PSP has already moved customer money. The ledger records that fact.
- Customer eligibility and refund decisions happen before the ledger.
- The ledger still owns tax, revenue and merchant accounting allocation.
- One currency (EUR) but several countries with different taxes (one country - one tax).
- Full and partial refunds can retain or return MoR revenue.
- Unknown merchant or tax data is saved as `HELD` for manual review.
- A negative merchant balance is carried forward. Alert after 14 days.
- Production errors need a durable exception queue and manual resolution.
- Fraud operation just alerting - not skipped

![Payment and refund flows](docs/images/event-flows.svg)

## Payments

An authenticated, readable capture is accepted because the customer was already
charged.

| Event                          | Result                                          |
|--------------------------------|-------------------------------------------------|
| Known merchant and tax         | `SUCCESS`: tax + MoR revenue + merchant balance |
| Unknown merchant or tax        | Save as `HELD`; manual review                   |
| Exact retry                    | Return `200`; write nothing                     |
| Same reference, different data | Keep the first event; manual exception          |
| Bad signature/body             | Return `401`/`400`                              |

## Refunds

A successful refund is also an existing money fact. Full, partial and multiple
refunds are supported.

| Event                               | Result                                                                 |
|-------------------------------------|------------------------------------------------------------------------|
| Missing payment                     | Return `404`; retry after payment arrives                              |
| Exact retry                         | Return `200`; write nothing                                            |
| Same reference, different data      | Keep the first event; manual exception                                 |
| Total refunds exceed payment amount | Save full refund; close payment; post excess to `OVER_REFUND_SUSPENSE` |
| Bad signature/body                  | Return `401`/`400`                                                     |

| Revenue policy | Partial refund                  | Full refund     | Reasons today                          |
|----------------|---------------------------------|-----------------|----------------------------------------|
| Keep fee       | Merchant funds refund after tax | Keep full fee   | Customer request, product issue, other |
| Return fee     | Return proportional fee         | Return full fee | Duplicate, fraud                       |

For an over-refund, return `200` after saving three linked facts: the complete
refund, balanced ledger entries with the excess in `OVER_REFUND_SUSPENSE`, and
a manual exception. Tax, revenue and merchant balances are never reversed past
their original amounts. An operator later moves suspense to PSP receivable,
merchant receivable or MoR loss. Suspense and the exception queue are planned;
the current code only records and logs the over-refund.

## Settlement and controls

![Settlement cycle](docs/images/settlement-cycle.svg)

| Process                   | Rule                                                 | Status      |
|---------------------------|------------------------------------------------------|-------------|
| Merchant payout           | Daily positive balance; exclude `HELD`               | Schema only |
| Negative merchant balance | Carry forward; alert after 14 days                   | Planned     |
| Tax settlement            | Pay per country and filing calendar, usually monthly | Planned     |
| Tax rates                 | Version with `valid_from`; cache; freeze on capture  | Table only  |
| PSP reconciliation        | Compare PSP and ledger totals daily                  | Planned     |

Hourly payout is a later option. It changes scheduling, not accounting.

## Merchant payout

- At the end of each London business day, calculate each merchant's positive
  `MERCHANT` balance. Exclude `HELD` funds.
- A non-positive balance is carried forward and future sales offset it.
- If a merchant remains negative for 14 days, alert operations.

## Architecture status

**Works now:** synchronous posting, idempotency, balanced entries, held payments,
cumulative refunds, concurrent-refund serialization, and exact decimal money.

**Missing for production:** raw-event inbox, suspense/exception workflow,
payout worker, negative-balance recovery, tax feed/cache, tax payment, PSP
reconciliation, database failover and load tests.

## Expected scale

- Capacity is measured in financial events
- Expectations - not benchmarks.
- “Users” means registered accounts;
- “customers” means accounts with at least one payment.

| Stage           |  Events/day | Avg / peak TPS |   DAU / MAU |  Total users | Paying customers |    Merchants | Required work                                                                  |
|-----------------|------------:|---------------:|------------:|-------------:|-----------------:|-------------:|--------------------------------------------------------------------------------|
| Easy today      | 10 thousand |        0.1 / 1 |    3k / 17k | 100 thousand |      25 thousand |          100 | Current design                                                                 |
| Expected target |   2 million |       23 / 230 | 670k / 3.3m |   20 million |        5 million |   1 thousand | 2–4 app instances, PostgreSQL HA, load tests, partitions and balance snapshots |
| Growth          |  20 million |     230 / 2.3k |  6.7m / 33m |  100 million |       50 million |  10 thousand | Read replicas, parallel payouts and snapshots; evaluate merchant sharding      |
| Large scale     | 100 million |     1.2k / 12k |  33m / 167m |  500 million |      250 million | 100 thousand | Architecture change: queue, database shards and per-shard tax aggregation      |

Those numbers must be changed after the load testing!!!

## Decisions before production

1. Persist refund-before-payment as pending, or rely on PSP retry?
2. Is fee policy global, per merchant, or per product?
3. What contract allows recovery after 14 negative days?
4. Who can resolve suspense, and which evidence is required?
