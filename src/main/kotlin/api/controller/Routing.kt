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
import org.example.api.model.FailureReason
import org.example.api.security.PspSignature
import org.example.service.PaymentResult
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Routes")

/** additionalProperties:false in the spec, so an unknown field is a contract break. */
private val json = Json { ignoreUnknownKeys = false }

/**
 * Server-to-server endpoints. Only the PSP calls these, and every one of them
 * moves money.
 *
 * Operational routes (health, Swagger, the spec) live in DevRoutes so the two
 * can be secured, rate-limited and exposed differently: in production these
 * sit behind the PSP's IP allowlist and signature, while the dev routes
 * either stay internal or are switched off entirely.
 */
fun Application.apiRoutes(
    controller: LedgerController,
    signature: PspSignature,
) {
    routing {

        post("/v1/payments/capture") {
            // Raw body first: the signature covers the exact bytes the PSP
            // sent. Deserialising and re-encoding would break it.
            val raw = call.receiveText()

            if (!signature.verify(raw.toByteArray(), call.request.headers[PspSignature.HEADER])) {
                log.warn("rejected unsigned payment from {}", call.request.local.remoteHost)
                call.respond(
                    HttpStatusCode.Unauthorized,
                    PaymentResponseDto.failed(FailureReason.INVALID_REQUEST),
                )
                return@post
            }

            val body = runCatching { json.decodeFromString<PaymentRequestDto>(raw) }
                .getOrElse { e ->
                    log.warn("malformed payment body: {}", e.message)
                    call.respond(
                        HttpStatusCode.BadRequest,
                        PaymentResponseDto.failed(FailureReason.INVALID_REQUEST),
                    )
                    return@post
                }

            val (result, response) = controller.createPayment(body)

            // 200 means "durably recorded", not "fully processed". A HELD
            // capture is recorded, so it is a 200 - the PSP must stop
            // retrying, or we end up with money in the bank and no row.
            val status = when (result) {
                is PaymentResult.Rejected -> HttpStatusCode.UnprocessableEntity
                else -> HttpStatusCode.OK
            }

            call.respond(status, response)
        }
    }
}
