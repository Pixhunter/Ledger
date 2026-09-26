package org.example.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.example.config.DatabaseConfig
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import javax.sql.DataSource

object Database {

    /** Connection settings come from config/application.yaml - not duplicated here. */
    fun dataSource(config: DatabaseConfig): DataSource {
        val cfg = HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password
            maximumPoolSize = config.poolSize
            connectionTimeout = config.connectionTimeoutMs
            validationTimeout = minOf(config.connectionTimeoutMs, 5_000)
            connectionInitSql = "SET statement_timeout = ${config.statementTimeoutMs}; " +
                "SET lock_timeout = ${config.lockTimeoutMs}; " +
                "SET idle_in_transaction_session_timeout = ${config.statementTimeoutMs}"
            isAutoCommit = false
        }
        return HikariDataSource(cfg)
    }

    fun dslContext(dataSource: DataSource): DSLContext =
        DSL.using(dataSource, SQLDialect.POSTGRES)
}
