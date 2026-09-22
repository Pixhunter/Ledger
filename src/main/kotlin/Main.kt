package org.example

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.example.api.controller.LedgerController
import org.example.api.controller.routes
import org.example.config.AppConfig
import org.example.db.Database
import org.example.db.Migrations
import org.example.scheduler.JobRunner
import org.example.statemachine.StateMachineService
import org.example.statemachine.payment.PaymentMachine
import org.example.scheduler.repository.JooqJobRepository
import org.example.statemachine.general.repository.JooqTaskRepository
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Main")

fun main() {
    val config = AppConfig.load()
    log.info("config loaded: ${config.server.host}:${config.server.port} -> ${config.database.url}")

    val dataSource = Database.dataSource(config.database)

    // Before anything touches a table.
    Migrations.run(dataSource)

    val dsl = Database.dslContext(dataSource)

    // Two repositories over the same tables: the API side creates and reads
    // tasks, the worker side claims and moves them. Neither can reach the
    // other's operations.
    val tasks = JooqTaskRepository(dsl)
    val jobs = JooqJobRepository(dsl)

    val controller = LedgerController(StateMachineService(tasks))

    // The runner lives in the same process for now. It shares nothing with the
    // HTTP side but the repository - all coordination happens in the database,
    // so moving it to its own deployment later needs no code change.
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    JobRunner(jobs, PaymentMachine()).start(scope)

    Runtime.getRuntime().addShutdownHook(Thread { scope.cancel("shutdown") })

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
