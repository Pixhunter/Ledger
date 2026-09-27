package org.example.api.controller

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.example.utils.Constants.Dev.SERVERS_BLOCK
import java.io.File

/**
 * Health and the Swagger UI over both specs. Not part of either API contract -
 * this is how the two contracts are read.
 */
class DocsController {

    fun routes(route: Route) = with(route) {
        get("/health") { call.respond(HttpStatusCode.OK, HealthDto("UP")) }

        get("/swagger") { call.respondText(SWAGGER_PAGE, ContentType.Text.Html) }

        get("/openapi.yaml") { call.respondSpec(File("api/api.yaml")) }

        get("/dev-api.yaml") { call.respondSpec(File("api/dev-api.yaml")) }

        get("/definitions.yaml") { call.respondSpec(File("api/definitions.yaml"), inject = false) }
    }

    /**
     * A relative server keeps Swagger on the same origin as this application.
     * This also avoids CORS failures when localhost is opened by another alias.
     */
    private suspend fun ApplicationCall.respondSpec(spec: File, inject: Boolean = true) {
        if (!spec.isFile) {
            respond(HttpStatusCode.NotFound, "${spec.path} not found")
            return
        }

        val body = if (!inject) spec.readText() else
            SERVERS_BLOCK.replace(spec.readText(), "").trimEnd() +
                "\n\nservers:\n  - url: /\n"

        respondText(body, ContentType.parse("application/yaml"))
    }

    private companion object {
        val SWAGGER_PAGE = """
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
                <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-standalone-preset.js"></script>
                <script>
                  window.ui = SwaggerUIBundle({
                    dom_id: "#ui",
                    urls: [
                      { name: "Main API", url: "/openapi.yaml" },
                      { name: "Dev API", url: "/dev-api.yaml" }
                    ],
                    "urls.primaryName": "Main API",
                    presets: [
                      SwaggerUIBundle.presets.apis,
                      SwaggerUIStandalonePreset
                    ],
                    layout: "StandaloneLayout"
                  });
                </script>
              </body>
            </html>
        """.trimIndent()
    }
}

@Serializable
private data class HealthDto(val status: String)
