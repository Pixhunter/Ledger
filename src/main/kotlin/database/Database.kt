package org.example.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.jetbrains.annotations.BlockingExecutor
import org.example.config.DatabaseConfig
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import javax.sql.DataSource

@OptIn(ExperimentalCoroutinesApi::class)
object Database {

    /**
     * Where every blocking JDBC call runs, sized to the connection pool.
     * Set once at startup by [dataSource]; the default matches DatabaseConfig.
     */
    @Volatile
    var jdbc: @BlockingExecutor CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(DEFAULT_PARALLELISM)
        private set

    /** Connection settings come from config/application.yaml - not duplicated here. */
    fun dataSource(config: DatabaseConfig): DataSource {
        jdbc = Dispatchers.IO.limitedParallelism(config.poolSize)

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

    private const val DEFAULT_PARALLELISM = 10
}