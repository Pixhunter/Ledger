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
            isAutoCommit = false
        }
        return HikariDataSource(cfg)
    }

    fun dslContext(dataSource: DataSource): DSLContext =
        DSL.using(dataSource, SQLDialect.POSTGRES)
}
