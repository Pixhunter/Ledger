package org.example.repository

import org.example.api.io
import org.example.jooq.tables.references.PROCESSING_ERROR
import org.example.model.ProcessingError
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.example.utils.logger

class ProcessingErrorRepository(private val dsl: DSLContext) : ProcessingErrorStore {

    private val log = logger<ProcessingErrorRepository>()

    override suspend fun save(error: ProcessingError) = saveAll(listOf(error))

    override suspend fun saveAll(errors: List<ProcessingError>): Unit = io {
        if (errors.isEmpty()) return@io

        val stored = dsl.transactionResult { cfg ->
            insertProcessingErrors(DSL.using(cfg), errors)
        }

        if (stored > 0) {
            log.warn(
                "event=quarantine stored={} codes={}",
                stored, errors.map { it.code }.distinct(),
            )
        }
    }
}

internal fun insertProcessingError(db: DSLContext, error: ProcessingError): Int =
    insertProcessingErrors(db, listOf(error))

internal fun insertProcessingErrors(db: DSLContext, errors: List<ProcessingError>): Int {
    if (errors.isEmpty()) return 0

    val rows = errors.map { error ->
        DSL.row(
            error.id,
            error.eventType.id,
            error.externalReference,
            JSONB.valueOf(error.payload),
            error.code.id,
            error.detail,
        )
    }

    return db.insertInto(
        PROCESSING_ERROR,
        PROCESSING_ERROR.ID,
        PROCESSING_ERROR.EVENT_TYPE,
        PROCESSING_ERROR.EXTERNAL_REFERENCE,
        PROCESSING_ERROR.PAYLOAD,
        PROCESSING_ERROR.ERROR_CODE,
        PROCESSING_ERROR.ERROR_DETAIL,
    )
        .valuesOfRows(rows)
        .onConflict(
            PROCESSING_ERROR.EVENT_TYPE,
            PROCESSING_ERROR.EXTERNAL_REFERENCE,
            PROCESSING_ERROR.ERROR_CODE,
        )
        .doNothing()
        .execute()
}
