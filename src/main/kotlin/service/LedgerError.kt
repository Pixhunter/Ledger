package org.example.service

import org.example.model.enums.ProcessingErrorCode

data class LedgerError(
    val code: ProcessingErrorCode,
    val reference: String,
    val detail: String,
)
