package org.example.api.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.example.api.model.PaymentRequestDto
import org.example.api.model.PaymentResponseDto
import org.example.api.model.FailureReasonDto
import org.example.api.security.PspSignature
import org.example.service.PaymentResult
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Routes")

/** additionalProperties:false in the spec, so an unknown field is a contract break. */
private val json = Json { ignoreUnknownKeys = false }

/**
 * Server-to-server endpoints.
 */
fun Application.apiRoutes(
    controller: LedgerController,
    signature: PspSignature,
) {
    routing {

        post("/v1/payments/capture") {
            val raw = call.receiveText()

            if (!signature.verify(raw.toByteArray(), call.request.headers[PspSignature.HEADER])) {
                log.warn("rejected unsigned payment from {}", call.request.local.remoteHost)
                call.respond(
                    HttpStatusCode.Unauthorized,
                    PaymentResponseDto.failed(FailureReasonDto.INVALID_REQUEST),
                )
                return@post
            }

            val body = runCatching { json.decodeFromString<PaymentRequestDto>(raw) }
                .getOrElse { e ->
                    log.warn("malformed payment body: {}", e.message)
                    call.respond(
                        HttpStatusCode.BadRequest,
                        PaymentResponseDto.failed(FailureReasonDto.INVALID_REQUEST),
                    )
                    return@post
                }

            val (result, response) = controller.createPayment(body)

            val status = when (result) {
                is PaymentResult.Rejected -> HttpStatusCode.UnprocessableEntity
                else -> HttpStatusCode.OK
            }

            call.respond(status, response)
        }
    }
}
