package org.example.support

import model.DueTransfer
import model.TransferClient
import model.TransferResult
import model.TransferStore

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

class FakeTransferStore(due: List<DueTransfer> = emptyList()) : TransferStore {

    private val pending = due.toMutableList()
    val marked = mutableListOf<Pair<DueTransfer, String>>()
    val released = mutableListOf<DueTransfer>()
    var dueCalls = 0
        private set

    override suspend fun due(limit: Int): List<DueTransfer> {
        dueCalls++
        val claimed = pending.take(limit)
        pending.removeAll(claimed.toSet())
        return claimed
    }

    override suspend fun markSent(transfer: DueTransfer, externalReference: String) {
        marked += transfer to externalReference
    }

    override suspend fun release(transfer: DueTransfer) {
        released += transfer
    }
}
