package org.example.repository

import org.example.db.io
import org.example.jooq.tables.references.PROCESSING_ERROR
import org.example.model.ProcessingError
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory

class ProcessingErrorRepository(private val dsl: DSLContext) : ProcessingErrorStore {

    private val log = LoggerFactory.getLogger(ProcessingErrorRepository::class.java)

    override suspend fun save(error: ProcessingError): Unit = io {
        val stored = dsl.transactionResult { cfg ->
            org.jooq.impl.DSL.using(cfg)
                .insertInto(PROCESSING_ERROR)
                .set(PROCESSING_ERROR.ID, error.id)
                .set(PROCESSING_ERROR.EVENT_TYPE, error.eventType.id)
                .set(PROCESSING_ERROR.EXTERNAL_REFERENCE, error.externalReference)
                .set(PROCESSING_ERROR.PAYLOAD, JSONB.valueOf(error.payload))
                .set(PROCESSING_ERROR.ERROR_CODE, error.code.id)
                .set(PROCESSING_ERROR.ERROR_DETAIL, error.detail)
                .onConflict(
                    PROCESSING_ERROR.EVENT_TYPE,
                    PROCESSING_ERROR.EXTERNAL_REFERENCE,
                    PROCESSING_ERROR.ERROR_CODE,
                )
                .doNothing()
                .execute()
        }

        if (stored > 0) {
            log.error(
                "PROCESSING ERROR [{}] {} {}: {}",
                error.code, error.eventType, error.externalReference, error.detail,
            )
        }
    }
}
