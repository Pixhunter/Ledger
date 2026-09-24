package org.example

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import org.example.api.controller.LedgerController
import org.example.api.controller.apiRoutes
import org.example.api.controller.devRoutes
import org.example.api.generated.model.ErrorReasonDto
import org.example.api.rejected
import org.example.api.security.PspSignature
import org.example.config.AppConfig
import org.example.repository.PaymentRepository
import org.example.repository.RefundRepository
import org.example.service.MerchantRegistry
import org.example.service.PaymentService
import org.example.service.RefundFeePolicy
import org.example.service.RefundService
import org.example.tax.BasisPoints
import org.example.tax.TaxRates
import org.jooq.DSLContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("App")

fun Application.ledgerModule(
    config: AppConfig,
    dsl: DSLContext,
    merchants: MerchantRegistry = MerchantRegistry(),
) {
    val signature = PspSignature(config.psp.secret)
    if (!signature.enabled) {
        log.warn("PSP signature verification is OFF - set psp.secret before anything real")
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
        feePolicy = RefundFeePolicy.of(config.mor.refundFeeReturnedFor),
    )

    install(ContentNegotiation) { json() }

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            log.warn("bad request: {}", cause.message)
            call.respond(HttpStatusCode.BadRequest, rejected(ErrorReasonDto.INVALID_REQUEST))
        }
    }

    apiRoutes(LedgerController(payments, refunds, signature))
    devRoutes(config.server)
}
