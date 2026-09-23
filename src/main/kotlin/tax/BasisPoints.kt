package org.example.tax

/**
 * Basis points, not percent: integer percent cannot express 8.875% US sales
 * tax or 19.5% VAT. 2000 = 20.00%.
 */
@JvmInline
value class BasisPoints(val value: Int) {
    init {
        require(value in 0..10_000) { "basis points must be 0..10000, was $value" }
    }

    companion object {
        val ZERO = BasisPoints(0)
    }
}
