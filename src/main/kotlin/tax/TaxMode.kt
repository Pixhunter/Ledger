package org.example.tax

enum class TaxMode {
    /** `amount` already contains the tax. tax = gross - gross / (1 + rate) */
    INCLUSIVE,

    /** `amount` is the net price, tax is added on top. tax = net * rate */
    EXCLUSIVE,
}
