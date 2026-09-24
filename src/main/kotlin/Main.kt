package org.example

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.example.config.AppConfig
import org.example.db.Database
import org.example.db.Migrations
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Main")

fun main() {
    val config = AppConfig.load()
    log.info("config loaded: ${config.server.host}:${config.server.port} -> ${config.database.url}")

    val dataSource = Database.dataSource(config.database)
    Migrations.run(dataSource)

    val dsl = Database.dslContext(dataSource)

    embeddedServer(Netty, host = config.server.host, port = config.server.port) {
        ledgerModule(config, dsl)
    }.start(wait = true)
}
