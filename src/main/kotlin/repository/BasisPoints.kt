package org.example.repository

/**
 * Basis points, not percent: integer percent cannot express a 5.5% VAT or a
 * 2.9% fee. 2000 = 20.00%. Two decimal places of percent, so US local
 * rates such as 8.875% are out of range - see README, out of scope.
 */
@JvmInline
value class BasisPoints(val value: Int) {
    init {
        require(value in 0..10_000) { "basis points must be 0..10_000, was $value" }
    }

    companion object {
        val ZERO = BasisPoints(0)
    }
}