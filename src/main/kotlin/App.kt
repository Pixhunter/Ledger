package org.example

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import org.example.api.controller.LedgerController
import org.example.api.controller.BalancesController
import org.example.api.controller.apiRoutes
import org.example.api.controller.devRoutes
import org.example.api.apiJson
import org.example.api.generated.model.ErrorReasonDto
import org.example.api.rejected
import org.example.api.security.PspSignature
import org.example.config.AppConfig
import org.example.payout.PayoutCalculationJob
import org.example.payout.PayoutDisbursementJob
import org.example.payout.PayoutScheduler
import org.example.psp.AcceptingPspPayoutClient
import org.example.remittance.AcceptingTaxAuthorityClient
import org.example.remittance.TaxBalanceMonitorJob
import org.example.remittance.TaxRemittanceCalculationJob
import org.example.remittance.TaxRemittanceDisbursementJob
import org.example.repository.TaxRemittanceRepository
import org.example.repository.BalancesRepository
import org.example.repository.MerchantRepository
import org.example.repository.PaymentRepository
import org.example.repository.PayoutRepository
import org.example.repository.ProcessingErrorRepository
import org.example.repository.RefundRepository
import org.example.service.MerchantRegistry
import org.example.service.PaymentService
import org.example.service.RefundService
import org.example.tax.BasisPoints
import org.example.tax.TaxRates
import org.jooq.DSLContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("App")

fun Application.ledgerModule(
    config: AppConfig,
    dsl: DSLContext,
    merchants: MerchantRegistry = MerchantRepository(dsl),
) {
    val signature = PspSignature(config.psp.secret, config.psp.allowSecretHeader)
    if (config.psp.allowSecretHeader) {
        log.warn("psp.allowSecretHeader is ON - the shared secret is accepted in place of a signature")
    }
    if (!signature.enabled) {
        log.error("no psp.secret: every request will be rejected")
    }

    val payments = PaymentService(
        payments = PaymentRepository(dsl),
        rates = TaxRates(),
        merchants = merchants,
        feeRate = BasisPoints(config.mor.feeBasisPoints),
        morCountry = config.mor.country,
    )

    val refunds = RefundService(
        refunds = RefundRepository(dsl),
    )

    install(ContentNegotiation) { json(apiJson) }

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            log.warn("bad request: {}", cause.message)
            call.respond(HttpStatusCode.BadRequest, rejected(ErrorReasonDto.INVALID_REQUEST))
        }
        // Date parsing throws outside the IllegalArgumentException hierarchy.
        exception<java.time.DateTimeException> { call, cause ->
            log.warn("bad request: {}", cause.message)
            call.respond(HttpStatusCode.BadRequest, rejected(ErrorReasonDto.INVALID_REQUEST))
        }
    }

    val processingErrors = ProcessingErrorRepository(dsl)
    val payoutStore = PayoutRepository(dsl)
    val payoutCalculation = PayoutCalculationJob(payoutStore, processingErrors)
    val payoutDisbursement = PayoutDisbursementJob(payoutStore, AcceptingPspPayoutClient())

    val remittanceStore = TaxRemittanceRepository(dsl)
    val taxCalculation = TaxRemittanceCalculationJob(remittanceStore)
    val taxDisbursement = TaxRemittanceDisbursementJob(remittanceStore, AcceptingTaxAuthorityClient())

    val taxMonitor = TaxBalanceMonitorJob(remittanceStore, processingErrors)

    PayoutScheduler(
        payoutCalculation, payoutDisbursement,
        taxCalculation, taxDisbursement, taxMonitor,
    ).start(this)

    apiRoutes(
        LedgerController(payments, refunds, processingErrors, signature),
        BalancesController(BalancesRepository(dsl)),
    )
    devRoutes(config.server, payoutCalculation, payoutDisbursement, taxCalculation, taxDisbursement)
}
