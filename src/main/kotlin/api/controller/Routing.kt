package org.example.api.controller

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.example.api.model.PaymentRequestDto
import org.example.config.ServerConfig
import java.io.File

fun Application.routes(controller: LedgerController, server: ServerConfig) {
    routing {

        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "UP"))
        }

        post("/v1/payment/capture") {
            val body = call.receive<PaymentRequestDto>()
            val result = controller.createPayment(body)
            call.respond(HttpStatusCode.OK, result)
        }


        /**
         * Swagger UI served by this app, so its origin is this app's port.
         * Opening api/api.yaml in an IDE preview instead makes the browser
         * resolve paths against the IDE's own web server (:63342), which is
         * why requests from there 404.
         */
        get("/swagger") {
            call.respondText(SWAGGER_PAGE, ContentType.Text.Html)
        }

        /**
         * The spec is served with its `servers` block injected from config,
         * so the URL lives in exactly one place. api/api.yaml deliberately
         * declares no server of its own.
         */
        get("/openapi.yaml") {
            val spec = File("api/api.yaml")
            if (!spec.isFile) {
                call.respond(HttpStatusCode.NotFound, "api/api.yaml not found")
                return@get
            }

            val body = buildString {
                append(spec.readText().trimEnd())
                append("\n\nservers:\n  - url: ${server.effectivePublicUrl}\n")
            }
            call.respondText(body, ContentType.parse("application/yaml"))
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
      // Relative url: the spec and the definitions file it references are
      // both fetched from this same origin.
      window.ui = SwaggerUIBundle({ url: "/openapi.yaml", dom_id: "#ui" });
    </script>
  </body>
</html>
"""
