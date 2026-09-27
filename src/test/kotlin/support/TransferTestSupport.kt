package org.example.support

import org.example.repository.DueTransfer
import org.example.repository.TransferClient
import org.example.repository.TransferResult
import org.example.repository.TransferStore

class RecordingTransferClient(
    private val result: (DueTransfer) -> TransferResult = { TransferResult.Accepted("ext-${it.reference}") },
) : TransferClient {

    val sent = mutableListOf<DueTransfer>()

    override suspend fun send(transfer: DueTransfer): TransferResult {
        sent += transfer
        return result(transfer)
    }

    val references: List<String> get() = sent.map { it.reference }
}

class FakeTransferStore(private val due: List<DueTransfer> = emptyList()) : TransferStore {

    val marked = mutableListOf<Pair<DueTransfer, String>>()
    val released = mutableListOf<DueTransfer>()

    override suspend fun due(limit: Int) = due.take(limit)

    override suspend fun markSent(transfer: DueTransfer, externalReference: String) {
        marked += transfer to externalReference
    }

    override suspend fun release(transfer: DueTransfer) {
        released += transfer
    }
}
