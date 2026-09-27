package org.example

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.example.config.AppConfig
import org.example.database.Database
import kotlinx.coroutines.runBlocking
import org.example.bootstrap.ledgerModule
import org.example.database.Migrations
import org.example.bootstrap.MerchantSeeder
import org.example.repository.MerchantRepository
import org.example.utils.logger


private val log = logger("ledger")

fun main() {
    val config = AppConfig.load()
    // The JDBC url is not logged: it carries credentials. GDPR Art. 32.
    log.info(
        "event=startup host={} port={} db={}",
        config.server.host, config.server.port, config.database.url.substringAfterLast('/'),
    )

    val dataSource = Database.dataSource(config.database)
    Migrations.run(dataSource)

    val dsl = Database.dslContext(dataSource)

    config.mor.merchantSeedResource
        ?.takeIf { it.isNotBlank() }
        ?.let { runBlocking { MerchantSeeder(MerchantRepository(dsl), it).run() } }

    embeddedServer(Netty, host = config.server.host, port = config.server.port) {
        ledgerModule(config, dsl)
    }.start(wait = true)
}
