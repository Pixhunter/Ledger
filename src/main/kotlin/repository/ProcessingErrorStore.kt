package org.example.repository

import org.example.model.ProcessingError

interface ProcessingErrorStore {
    suspend fun save(error: ProcessingError)
}
