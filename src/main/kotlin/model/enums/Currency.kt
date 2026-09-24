package org.example.model.enums

import org.example.model.EnumId

/** EUR only for now. The ledger is already per-currency, so adding one is a new entry here. */
enum class Currency(override val id: Short) : EnumId {
    EUR(1)
    ;

    companion object {
        fun byCode(code: String): Currency? = entries.firstOrNull { it.name == code }
    }
}
