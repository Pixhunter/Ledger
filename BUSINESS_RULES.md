# Business rules for MoR ledger service

This document explains the business behaviour of the Merchant of Record (MoR)
ledger service.

## Service architecture assumptions

- The ledger receives PSP notifications and must not lose valid money events.
- The PSP has already moved customer money. The ledger records that fact.
- Customer eligibility and refund decisions happen before the ledger.
- The ledger still owns tax, revenue and merchant accounting allocation.
- One currency (EUR) but several countries with different taxes (one country - one tax).
- Full and partial refunds keep MoR revenue; returning it by reason is a later step.
- Unknown merchant or tax data is saved as `HELD` and queued for manual review.
- A negative merchant balance is carried forward. Alert after 14 days.
- Production errors need a durable exception queue and manual resolution.
- Fraud operation just alerting - not skipped

![Payment and refund flows](docs/images/event-flows.svg)

## Payments

An authenticated, readable capture is accepted because the customer was already
charged.

| Event                          | Result                                                 |
|--------------------------------|--------------------------------------------------------|
| Known merchant and tax         | `SUCCESS`: tax + MoR revenue + merchant balance        |
| Unknown merchant or tax        | Save as `HELD`; write an exception; manual review      |
| `success = false` at the PSP   | Return `200`; write nothing                            |
| Exact retry                    | Return `200`; write nothing                            |
| Same reference, different data | Keep the first event; write an exception; no second row |
| Bad signature/body             | Return `401`/`400`                                     |

The merchant allowlist is empty until `mor.merchant` is read, so every capture
is `HELD` today. The rule is implemented; the source of merchants is not.

## Refunds

A successful refund is also an existing money fact. Full, partial and multiple
refunds are supported.

| Event                               | Result                                                                 |
|-------------------------------------|------------------------------------------------------------------------|
| Missing payment                     | Return `404`; retry after payment arrives                              |
| Exact retry                         | Return `200`; write nothing                                            |
| Same reference, different data      | Keep the first event; write an exception; no second row                |
| Total refunds exceed payment amount | Save full refund; close payment; post excess to `SUSPENSE`; write an exception |
| Bad signature/body                  | Return `401`/`400`                                                     |

| Revenue policy   | Partial refund                  | Full refund   | Today                         |
|------------------|---------------------------------|---------------|-------------------------------|
| Keep fee         | Merchant funds refund after tax | Keep full fee | Always                        |
| Return fee       | Return proportional fee         | Return full fee | Not implemented - see below |

Returning the fee depends on who is at fault, and the `reason` in the webhook
body cannot carry that: the signature proves the PSP sent it, not who wrote it.
Production takes the reason from the MoR's own refund service. `fee_returned`
is stored per refund, so switching later never rewrites history.

For an over-refund, return `200` after saving three linked facts: the complete
refund, balanced ledger entries with the excess in `SUSPENSE`, and
a manual exception. Tax, revenue and merchant balances are never reversed past
their original amounts. An operator later moves suspense to PSP receivable,
merchant receivable or MoR loss with a new ledger transaction, then deletes the
exception row. Implemented: the refund, the `SUSPENSE` entries and the exception
row. Not implemented: the operator action that clears them.

## Settlement and controls

![Settlement cycle](docs/images/settlement-cycle.svg)

| Process                   | Rule                                                 | Status      |
|---------------------------|------------------------------------------------------|-------------|
| Merchant payout           | Daily positive balance; exclude `HELD`; fixed cutoff | Implemented |
| Negative merchant balance | Carry forward; alert after 14 days                   | Planned     |
| Tax settlement            | Pay per country and filing calendar, usually monthly | Planned     |
| Tax rates                 | Version with `valid_from`; cache; freeze on capture  | Implemented |
| PSP reconciliation        | Compare PSP and ledger totals daily                  | Planned     |

Hourly payout is a later option. It changes scheduling, not accounting.

## Merchant payout

- At the end of each London business day, calculate each merchant's positive
  `MERCHANT` balance. Exclude `HELD` funds.
- Every day, process all unsettled entries received before one fixed cutoff.
- A late PSP payment, even from an older day, enters the next batch. Future-dated
  payments wait until eligible.
- A `payment_time` over seven days from receipt is recorded as an operational
  warning; the payment is still stored and processed.
- A non-positive balance is carried forward and future sales offset it.
- If a merchant remains negative for 14 days, alert operations.

## Tax category

Only the standard tax category is supported for this task. Reduced and special
categories require merchant-specific configuration in a future version.

VAT ID formats are versioned by country and `valid_from`. A valid format permits
reverse charge. An invalid format creates `INVALID_VAT_ID`; the payment still
uses normal consumer tax. Live VIES registration checks are future work.

Two matching location signals are preferred for the tax country. For this task,
billing country remains the fallback; weak evidence creates
`WEAK_TAX_COUNTRY_EVIDENCE`. Stronger verification is future work.

## Architecture status

**Works now:** synchronous posting, idempotency with conflict detection,
balanced entries, held payments, cumulative refunds, concurrent-refund
serialization, exact decimal money, over-refund suspense, and the
`processing_error` exception queue.

**Missing for production:** merchant table, alert delivery, exception
resolution and replay, payout worker, negative-balance recovery, tax feed and
versioned rates, tax payment, PSP reconciliation, database failover and load
tests.

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
