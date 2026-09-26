package org.example.api.controller

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.example.config.ServerConfig
import org.example.payout.PayoutCalculationJob
import org.example.payout.PayoutDisbursementJob
import org.example.remittance.TaxRemittanceCalculationJob
import org.example.remittance.TaxRemittanceDisbursementJob
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

fun Application.devRoutes(
    server: ServerConfig,
    payoutCalculation: PayoutCalculationJob,
    payoutDisbursement: PayoutDisbursementJob,
    taxCalculation: TaxRemittanceCalculationJob,
    taxDisbursement: TaxRemittanceDisbursementJob,
) {
    routing {

        // Manual triggers for the scheduled jobs. Operational only.
        post("/v1/payouts/compute") {
            val today = LocalDate.now(LONDON)
            val computed = payoutCalculation.run(today)

            call.respond(
                HttpStatusCode.OK,
                PayoutRunResponse(
                    payoutDate = today.toString(),
                    merchants = computed.size,
                    totalAmount = computed.fold(BigDecimal.ZERO) { sum, it -> sum + it.amount }.toPlainString(),
                    payouts = computed.map {
                        PayoutResponse(
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
            call.respond(HttpStatusCode.OK, mapOf("sent" to payoutDisbursement.run()))
        }

        post("/v1/tax-remittances/compute") {
            val period = LocalDate.now(LONDON).withDayOfMonth(1)
            val computed = taxCalculation.run(period)

            call.respond(
                HttpStatusCode.OK,
                TaxRunResponse(
                    period = period.toString(),
                    countries = computed.size,
                    totalAmount = computed.fold(BigDecimal.ZERO) { sum, it -> sum + it.amount }.toPlainString(),
                    remittances = computed.map {
                        TaxRemittanceResponse(
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
            call.respond(HttpStatusCode.OK, mapOf("sent" to taxDisbursement.run()))
        }

        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "UP"))
        }

        get("/swagger") {
            call.respondText(SWAGGER_PAGE, ContentType.Text.Html)
        }

        get("/openapi.yaml") {
            call.respondSpec(File("api/api.yaml"), server.effectivePublicUrl)
        }

        get("/dev-api.yaml") {
            call.respondSpec(File("api/dev-api.yaml"), server.effectivePublicUrl)
        }

        get("/definitions.yaml") {
            val defs = File("api/definitions.yaml")
            if (defs.isFile) {
                call.respondText(defs.readText(), ContentType.parse("application/yaml"))
            } else {
                call.respond(HttpStatusCode.NotFound, "api/definitions.yaml not found")
            }
        }
    }
}

private val LONDON: ZoneId = ZoneId.of("Europe/London")

@Serializable
private data class PayoutRunResponse(
    val payoutDate: String,
    val merchants: Int,
    val totalAmount: String,
    val payouts: List<PayoutResponse>,
)

@Serializable
private data class PayoutResponse(
    val merchantId: String,
    val merchantName: String,
    val payments: Int,
    val amount: String,
)

@Serializable
private data class TaxRunResponse(
    val period: String,
    val countries: Int,
    val totalAmount: String,
    val remittances: List<TaxRemittanceResponse>,
)

@Serializable
private data class TaxRemittanceResponse(
    val country: String,
    val payments: Int,
    val ratePercent: String,
    val amount: String,
)

private val SERVERS_BLOCK = Regex("""(?m)^servers:\n(?:[ \t-].*\n?)*""")

/** The spec carries no servers block: the app injects where it is actually listening. */
private suspend fun ApplicationCall.respondSpec(spec: File, publicUrl: String) {
    if (!spec.isFile) {
        respond(HttpStatusCode.NotFound, spec.path + " not found")
        return
    }

    val body = SERVERS_BLOCK.replace(spec.readText(), "").trimEnd() +
        "\n\nservers:\n  - url: " + publicUrl + "\n"

    respondText(body, ContentType.parse("application/yaml"))
}

private val SWAGGER_PAGE = """
        <!doctype html>
        <html>
          <head>
            <meta charset="utf-8">
            <title>MoR Ledger API</title>
            <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css">
          </head>
          <body>
            <div id="ui"></div>
            <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
            <script>
              window.ui = SwaggerUIBundle({
                dom_id: "#ui",
                urls: [
                  { name: "PSP API", url: "/openapi.yaml" },
                  { name: "Dev API", url: "/dev-api.yaml" }
                ]
              });
            </script>
          </body>
        </html>
"""
