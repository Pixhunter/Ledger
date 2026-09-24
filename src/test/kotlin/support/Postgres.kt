package org.example.support

import org.example.config.DatabaseConfig
import org.example.db.Database
import org.example.db.Migrations
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/**
 * One Postgres for the whole test class run.
 *
 * Not one per class: a container costs 3-5 seconds to start, and the schema
 * every class needs is identical. Tests get isolation from truncation between
 * them, which takes milliseconds, not from a fresh database.
 */
object Postgres {

    private const val IMAGE = "postgres:16-alpine"
    private const val SCHEMA = "mor"

    val available: Boolean =
        runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)

    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer(IMAGE).apply { start() }
    }

    val dsl: DSLContext by lazy {
        val dataSource = Database.dataSource(
            DatabaseConfig(
                url = container.jdbcUrl,
                user = container.username,
                password = container.password,
                poolSize = 2,
            )
        )
        Migrations.run(dataSource)
        Database.dslContext(dataSource)
    }

    fun clean() = dsl.transaction { cfg ->
        val db = DSL.using(cfg)

        val tables = db
            .resultQuery(
                "select quote_ident(schemaname) || '.' || quote_ident(tablename) " +
                    "from pg_tables where schemaname = ?",
                SCHEMA,
            )
            .fetch()
            .map { it.get(0, String::class.java) }

        if (tables.isNotEmpty()) {
            db.execute("truncate ${tables.joinToString()} restart identity cascade")
        }
    }
}
