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
import org.example.api.controller.routes
import org.example.config.AppConfig
import org.example.db.Database
import org.example.db.Migrations
import org.example.ledger.repository.JooqLedgerRepository
import org.example.service.PaymentService
import org.example.tax.BasisPoints
import org.example.tax.TaxRates
import org.example.tax.TaxService
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Main")

fun main() {
    val config = AppConfig.load()
    log.info("config loaded: ${config.server.host}:${config.server.port} -> ${config.database.url}")

    val dataSource = Database.dataSource(config.database)

    // Before anything touches a table.
    Migrations.run(dataSource)

    val dsl = Database.dslContext(dataSource)

    val ledger = JooqLedgerRepository(dsl)
    val taxes = TaxService(ledger, TaxRates(), feeRate = BasisPoints(500))
    val controller = LedgerController(PaymentService(taxes))

    embeddedServer(Netty, host = config.server.host, port = config.server.port) {
        install(ContentNegotiation) { json() }

        // Validation lives in PaymentModel.from(); this turns its complaint
        // into a 400 with the reason, instead of a 500 with a stack trace.
        install(StatusPages) {
            exception<IllegalArgumentException> { call, cause ->
                log.warn("bad request: ${cause.message}")
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to (cause.message ?: "invalid request"))
                )
            }
        }

        routes(controller, config.server)
    }.start(wait = true)
}
