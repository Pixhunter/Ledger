package org.example.database

import org.example.utils.Constants
import org.flywaydb.core.Flyway
import javax.sql.DataSource
import org.example.utils.logger

/**
 * Migrations run at application startup, not from a separate container.
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