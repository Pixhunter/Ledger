package org.example.database

import org.example.utils.Constants
import org.flywaydb.core.Flyway
import javax.sql.DataSource
import org.example.utils.logger

/**
 * Migrations run at application startup, not from a separate container.
 *
 * Flyway records every applied script in flyway_schema_history, so calling
 * this on every boot is a no-op once the schema is current, and an edited
 * script fails the checksum check instead of silently running twice.
 *
 * Trade-off: with several instances booting at once they all try to migrate.
 * Flyway takes a lock so only one wins and the rest wait - correct, but it
 * couples deploys to schema changes. For a multi-instance production deploy,
 * migrating as a separate release step is the safer shape.
 */
object Migrations {

    private val log = logger<Migrations>()

    fun run(dataSource: DataSource) {
        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations("filesystem:db/migration", "classpath:db/migration")
            .defaultSchema(Constants.Config.FLYWAY_HISTORY_SCHEMA)
            .createSchemas(true)
            .load()
            .migrate()

        log.info(
            "migrations applied: {}, schema now at {}",
            result.migrationsExecuted,
            result.targetSchemaVersion ?: "baseline",
        )
    }
}