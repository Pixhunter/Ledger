package org.example.model

import org.example.model.enums.EventType
import org.example.model.enums.ProcessingErrorCode
import java.util.UUID

data class ProcessingError(
    val id: UUID,
    val eventType: EventType,
    val externalReference: String,
    val payload: String,
    val code: ProcessingErrorCode,
    val detail: String,
)
