package org.example.api.controller

import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.example.api.security.PspSignature

/** Server-to-server endpoints. */
fun Application.apiRoutes(controller: LedgerController) {
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
    }
}
