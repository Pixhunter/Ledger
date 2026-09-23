package org.example.api.controller

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.example.config.ServerConfig
import java.io.File

fun Application.devRoutes(server: ServerConfig) {
    routing {

        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "UP"))
        }

        get("/swagger") {
            call.respondText(SWAGGER_PAGE, ContentType.Text.Html)
        }

        get("/openapi.yaml") {
            val spec = File("api/api.yaml")
            if (!spec.isFile) {
                call.respond(HttpStatusCode.NotFound, "api/api.yaml not found")
                return@get
            }

            val withoutServers = SERVERS_BLOCK.replace(spec.readText(), "")
            val body = buildString {
                append(withoutServers.trimEnd())
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

private val SERVERS_BLOCK = Regex("""(?m)^servers:\n(?:[ \t-].*\n?)*""")

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
              window.ui = SwaggerUIBundle({ url: "/openapi.yaml", dom_id: "#ui" });
            </script>
          </body>
        </html>
"""
