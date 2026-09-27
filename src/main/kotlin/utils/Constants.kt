package org.example.utils

import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.LocalTime
import java.time.ZoneId

/**
 * Every tunable number, window and literal the service decides by, in one
 * place. Values a type owns as part of its own vocabulary stay on that type:
 * `Money.ZERO`, `BasisPoints.ZERO` and the enum ids are not settings.
 *
 * Anything here that an operator would want to change per environment belongs
 * in config/application.yaml instead; these are the ones fixed at build time.
 */
object Constants {

    object Amounts {
        /** Every money column is numeric(_, 4). */
        const val STORAGE_SCALE = 4

        /** Guard digits kept during division before rounding back to the currency. */
        const val DIVISION_GUARD_DIGITS = 8
        const val DIVISION_SCALE = STORAGE_SCALE + DIVISION_GUARD_DIGITS

        const val MAX_INTEGER_DIGITS = 15

        /** Basis points denominator: 10000 bps = 100%. */
        val TEN_THOUSAND: BigDecimal = BigDecimal("10000")
    }

    object Api {
        const val PSP_SIGNATURE_HEADER = "X-Psp-Signature"
        const val PSP_SIGNATURE_ALGORITHM = "HmacSHA256"

        const val DEFAULT_PAGE_LIMIT = 100
        const val MAX_PAGE_LIMIT = 500
        const val MAX_MERCHANT_IDS = 100

        val COUNTRY_CODE = Regex("^[A-Z]{2}$")

        /** PSP and refund references share one format. */
        val EXTERNAL_REFERENCE = Regex("^[A-Za-z0-9_-]{1,64}$")
        val VAT_ID = Regex("^[A-Z]{2}[A-Za-z0-9]{2,13}$")
    }

    object Dates {
        /**
         * A capture this far from now is still booked, with a warning: money
         * received is money received, only its dating is suspect.
         */
        val PAYMENT_MAX_DRIFT: Duration = Duration.ofDays(7)

        /**
         * A refund is booked at its tax point, so a future date moves tax out
         * of the current period. Clock skew and a PSP batching a day's refunds
         * overnight are worth tolerating; a month is not - that is a bad feed,
         * and it belongs in the error table, not in the ledger.
         */
        val REFUND_MAX_FUTURE_DRIFT: Duration = Duration.ofDays(2)

        /**
         * Postgres timestamptz keeps microseconds. Instant.now() keeps
         * nanoseconds on Linux and microseconds on macOS, so an inbound
         * timestamp is truncated to what the column can hold. Otherwise a
         * replay read back from the row never equals the request that wrote
         * it, and every PSP retry is quarantined as an idempotency conflict.
         */
        val STORED_PRECISION: ChronoUnit = ChronoUnit.MICROS
    }

    object Fees {
        /** The MoR keeps its fee on every refund; the merchant covers it. */
        const val FEE_RETURNED_ON_REFUND = false
    }

    object Jobs {
        val REPORTING_ZONE: ZoneId = ZoneId.of("Europe/London")

        val PAYOUT_CALCULATION_AT: LocalTime = LocalTime.MIDNIGHT
        val PAYOUT_DISBURSEMENT_AT: LocalTime = LocalTime.of(1, 0)
        val TAX_MONITOR_AT: LocalTime = LocalTime.of(1, 30)
        val TAX_CALCULATION_AT: LocalTime = LocalTime.of(2, 0)
        val TAX_DISBURSEMENT_AT: LocalTime = LocalTime.of(3, 0)

        /** Tax is filed on the 5th for the month before: late refunds get a few days to land. */
        const val TAX_FILING_DAY = 5

        const val MERCHANT_NEGATIVE_DAYS_LIMIT = 14
        const val TAX_NEGATIVE_DAYS_LIMIT = 3

        const val RETRY_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 100L
        const val BATCH_SIZE = 500
        const val MAX_BATCH_SIZE = 1000

        /** Rows one disbursement worker claims per pass. */
        const val CLAIM_LIMIT = 100

        /** A claim older than this is treated as a dead worker and retried. */
        const val CLAIM_TIMEOUT_MINUTES = 5
    }

    object Config {
        const val DEFAULT_PATH = "config/application.yaml"
        const val FLYWAY_HISTORY_SCHEMA = "public"
    }

    object Sources {
        const val EC_VAT_RATES_2021 = "European Commission VAT rates, 1 January 2021"
        const val EU_VAT_ID_BASELINE = "EU VAT identification format baseline"

        /** Both seeded reference sets are valid from this date. */
        val VALID_FROM: LocalDate = LocalDate.of(2021, 1, 1)
    }

    object Dev {
        /** Rewrites the `servers:` block so Swagger posts to the running app. */
        val SERVERS_BLOCK = Regex("""(?m)^servers:\n(?:[ \t-].*\n?)*""")
    }
}