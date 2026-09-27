package org.example.support

import model.DueTransfer
import model.TransferClient
import model.TransferResult
import model.TransferStore
import model.SentTransfer

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

    override suspend fun markSent(transfers: List<SentTransfer>): Int {
        marked += transfers.map { it.transfer to it.externalReference }
        return transfers.size
    }

    override suspend fun release(transfers: List<DueTransfer>): Int {
        released += transfers
        return transfers.size
    }
}
