package org.example.db

import org.slf4j.LoggerFactory
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * Migrations run at application startup, not from a separate container.
 *
 * Flyway records every applied script in flyway_schema_history, so calling
 * this on every boot is a no-op once the schema is current, and an edited
 * script fails the checksum check instead of silently running twice.
 *
 * Trade-off: with several instances booting at once they all try to migrate.
 * Flyway takes a lock so only one wins and the rest wait - correct, but it
 * does couple deploys to schema changes. For a multi-instance production
 * deploy, migrating as a separate release step is the safer shape.
 */
object Migrations {

    private val log = LoggerFactory.getLogger(Migrations::class.java)

    fun run(dataSource: DataSource) {
        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations("filesystem:db/migration", "classpath:db/migration")
            .load()
            .migrate()

        log.info("migrations applied: ${result.migrationsExecuted}, schema now at ${result.targetSchemaVersion ?: "baseline"}")
    }
}
