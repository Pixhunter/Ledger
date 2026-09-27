package org.example.api.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.example.service.scheduled.DisbursementJob
import org.example.service.scheduled.PayoutCalculationJob
import org.example.service.scheduled.TaxRemittanceCalculationJob
import org.example.utils.Constants.Jobs.REPORTING_ZONE
import org.example.utils.logger
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The dev API: manual triggers for the scheduled jobs, so a reviewer can drive
 * a full cycle from Swagger without waiting for midnight. Described by
 * api/dev-api.yaml, separate from the PSP and finance API.
 *
 * Operational only. Nothing here is part of the production contract.
 */
class DevController(
    private val payoutCalculation: PayoutCalculationJob,
    private val payoutDisbursement: DisbursementJob,
    private val taxCalculation: TaxRemittanceCalculationJob,
    private val taxDisbursement: DisbursementJob,
) {
    private val log = logger<DevController>()

    fun routes(route: Route) = with(route) {
        post("/v1/payouts/compute") {
            log.info("Got request to compute payouts for the merchants")

            val today = LocalDate.now(REPORTING_ZONE)
            val computed = payoutCalculation.run(today)

            call.respond(
                HttpStatusCode.OK,
                PayoutRunDto(
                    payoutDate = today.toString(),
                    merchants = computed.size,
                    totalAmount = computed.total { it.amount },
                    payouts = computed.map {
                        PayoutLineDto(
                            merchantId = it.merchantId.toString(),
                            merchantName = it.merchantName,
                            payments = it.payments,
                            amount = it.amount.toPlainString(),
                        )
                    },
                ),
            )
        }

        post("/v1/payouts/send") {
            log.info("Got request to send payouts for the merchants")
            call.respond(HttpStatusCode.OK, SentDto(payoutDisbursement.run()))
        }

        post("/v1/tax-remittances/compute") {
            log.info("Got request to compute payouts for the taxes")

            val period = LocalDate.now(REPORTING_ZONE).withDayOfMonth(1)
            val computed = taxCalculation.run(period)

            call.respond(
                HttpStatusCode.OK,
                TaxRunDto(
                    period = period.toString(),
                    countries = computed.size,
                    totalAmount = computed.total { it.amount },
                    remittances = computed.map {
                        TaxRemittanceLineDto(
                            country = it.country,
                            payments = it.payments,
                            ratePercent = it.ratePercent.toPlainString(),
                            amount = it.amount.toPlainString(),
                        )
                    },
                ),
            )
        }

        post("/v1/tax-remittances/send") {
            log.info("Got request to send payouts for the taxes")
            call.respond(HttpStatusCode.OK, SentDto(taxDisbursement.run()))
        }
    }

    private fun <T> List<T>.total(amount: (T) -> BigDecimal): String =
        fold(BigDecimal.ZERO) { sum, it -> sum + amount(it) }.toPlainString()
}

// TODO remove this class - that's for testing - not for real prod
//  based on it this dto didn't  generate

@Serializable
private data class SentDto(val sent: Int)

@Serializable
private data class PayoutRunDto(
    val payoutDate: String,
    val merchants: Int,
    val totalAmount: String,
    val payouts: List<PayoutLineDto>,
)

@Serializable
private data class PayoutLineDto(
    val merchantId: String,
    val merchantName: String,
    val payments: Int,
    val amount: String,
)

@Serializable
private data class TaxRunDto(
    val period: String,
    val countries: Int,
    val totalAmount: String,
    val remittances: List<TaxRemittanceLineDto>,
)

@Serializable
private data class TaxRemittanceLineDto(
    val country: String,
    val payments: Int,
    val ratePercent: String,
    val amount: String,
)
