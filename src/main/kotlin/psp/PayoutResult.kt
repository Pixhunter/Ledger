package org.example.psp

sealed interface PayoutResult {

    data class Accepted(val pspReference: String) : PayoutResult

    data class Failed(val reason: String) : PayoutResult
}
