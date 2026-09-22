package org.example.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs blocking JDBC work away from Ktor's request threads. */
internal suspend inline fun <R> io(crossinline block: () -> R): R =
    withContext(Dispatchers.IO) { block() }
