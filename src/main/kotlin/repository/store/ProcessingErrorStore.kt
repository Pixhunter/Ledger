package repository.store

import org.example.model.ProcessingError

interface ProcessingErrorStore {
    suspend fun save(error: ProcessingError)

    suspend fun saveAll(errors: List<ProcessingError>) {
        errors.forEach { save(it) }
    }
}