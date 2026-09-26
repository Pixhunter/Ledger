package org.example.api.controller

import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.example.api.security.PspSignature

/** Server-to-server endpoints. */
fun Application.apiRoutes(controller: LedgerController, balances: BalancesController) {
    routing {
        post("/v1/payment/capture") {
            val response = controller.createPayment(
                rawBody = call.receiveText(),
                signatureHeader = call.request.headers[PspSignature.HEADER],
            )
            call.respond(response.status, response.body)
        }

        post("/v1/payment/refund") {
            val response = controller.createRefund(
                rawBody = call.receiveText(),
                signatureHeader = call.request.headers[PspSignature.HEADER],
            )
            call.respond(response.status, response.body)
        }

        // Read-only finance reports. Not a PSP endpoint, so no signature: in
        // production this needs its own auth for the finance client.
        get("/v1/balances/tax") {
            val country = call.request.queryParameters["country"]
                ?: throw IllegalArgumentException("country is required")

            call.respond(
                balances.taxBalance(
                    country = country,
                    from = call.request.queryParameters["from"],
                    to = call.request.queryParameters["to"],
                )
            )
        }

        get("/v1/balances/merchants") {
            call.respond(
                balances.merchantBalances(
                    merchantIds = call.request.queryParameters.getAll("merchantId").orEmpty(),
                    date = call.request.queryParameters["date"],
                    asOf = call.request.queryParameters["asOf"],
                    after = call.request.queryParameters["after"],
                    limit = call.request.queryParameters["limit"],
                )
            )
        }
    }
}
