package org.example

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import org.example.api.controller.LedgerController
import org.example.api.controller.apiRoutes
import org.example.api.controller.devRoutes
import org.example.api.model.PaymentResponseDto
import org.example.api.model.FailureReason
import org.example.api.security.PspSignature
import org.example.config.AppConfig
import org.example.db.Database
import org.example.db.Migrations
import org.example.repository.JooqPaymentRepository
import org.example.service.InMemoryMerchantRegistry
import org.example.service.PaymentService
import org.example.tax.BasisPoints
import org.example.tax.TaxRates
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Main")

fun main() {
    val config = AppConfig.load()
    log.info("config loaded: ${config.server.host}:${config.server.port} -> ${config.database.url}")

    val dataSource = Database.dataSource(config.database)
    Migrations.run(dataSource)

    val dsl = Database.dslContext(dataSource)

    val paymentRepository = JooqPaymentRepository(dsl)
    val signature = PspSignature(config.psp.secret)
    if (!signature.enabled) {
        log.warn("PSP signature verification is OFF - set psp.secret before anything real")
    }

    val payments = PaymentService(
        payments = paymentRepository,
        rates = TaxRates(),
        merchants = InMemoryMerchantRegistry(),
        feeRate = BasisPoints(config.mor.feeBasisPoints),
        morCountry = config.mor.country,
    )

    val controller = LedgerController(payments)

    embeddedServer(Netty, host = config.server.host, port = config.server.port) {
        install(ContentNegotiation) { json() }

        // Validation lives in PaymentModel.from(); this turns its complaint
        // into 400 INVALID_REQUEST instead of a 500 with a stack trace.
        install(StatusPages) {
            exception<IllegalArgumentException> { call, cause ->
                log.warn("bad request: {}", cause.message)
                call.respond(
                    HttpStatusCode.BadRequest,
                    PaymentResponseDto.failed(FailureReason.INVALID_REQUEST),
                )
            }
        }

        apiRoutes(controller, signature)

        // Operational only. Bind elsewhere or drop entirely in production -
        // nothing here should be reachable from where the PSP calls in.
        devRoutes(config.server)
    }.start(wait = true)
}
