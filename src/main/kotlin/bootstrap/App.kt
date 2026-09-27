package org.example.bootstrap

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import org.example.api.controller.LedgerController
import org.example.api.controller.BalancesController
import org.example.api.controller.DevController
import org.example.api.controller.DocsController
import org.example.api.apiJson
import org.example.api.generated.model.ErrorReasonDto
import org.example.api.security.PspSignature
import org.example.service.scheduled.PayoutCalculationJob
import org.example.service.scheduled.PayoutScheduler
import org.example.service.scheduled.TaxBalanceMonitorJob
import org.example.service.scheduled.TaxRemittanceCalculationJob
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
import org.example.repository.BasisPoints
import org.example.service.TaxRates
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.example.api.adapter.AcceptingTransferClient
import org.example.service.scheduled.DisbursementJob
import org.example.repository.TransferKind
import io.ktor.server.routing.routing
import org.example.api.DtoMapper.rejected
import org.example.config.AppConfig
import java.time.DateTimeException

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
        exception<DateTimeException> { call, cause ->
            log.warn("bad request: {}", cause.message)
            call.respond(HttpStatusCode.BadRequest, rejected(ErrorReasonDto.INVALID_REQUEST))
        }
    }

    val transferClient = AcceptingTransferClient()
    val processingErrors = ProcessingErrorRepository(dsl)
    val payoutStore = PayoutRepository(dsl)
    val payoutCalculation = PayoutCalculationJob(payoutStore, processingErrors)
    val payoutDisbursement = DisbursementJob(TransferKind.PAYOUT, payoutStore, transferClient)

    val remittanceStore = TaxRemittanceRepository(dsl)
    val taxCalculation = TaxRemittanceCalculationJob(remittanceStore)
    val taxDisbursement = DisbursementJob(TransferKind.TAX, remittanceStore, transferClient)

    val taxMonitor = TaxBalanceMonitorJob(remittanceStore, processingErrors)

    PayoutScheduler(
        payoutCalculation, payoutDisbursement,
        taxCalculation, taxDisbursement, taxMonitor,
    ).start(this)

    routing {
        // Production API, described by api/api.yaml.
        LedgerController(payments, refunds, processingErrors, signature).routes(this)
        BalancesController(BalancesRepository(dsl)).routes(this)

        // Dev API, described by api/dev-api.yaml. Separate surface, separate spec.
        DevController(payoutCalculation, payoutDisbursement, taxCalculation, taxDisbursement).routes(this)

        DocsController(config.server).routes(this)
    }
}
