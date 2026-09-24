package org.example.support

import org.jooq.DSLContext
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.BeforeTest

abstract class PostgresTest {

    protected val dsl: DSLContext get() = Postgres.dsl

    @BeforeTest
    fun preparePostgres() {
        assumeTrue(Postgres.available, "Docker is not running - database tests skipped")
        Postgres.clean()
    }
}
