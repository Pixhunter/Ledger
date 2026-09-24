package org.example.model

import org.example.model.enums.Currency
import java.math.BigDecimal
import java.math.RoundingMode

/** Decimal money stored at four places and posted at the currency precision. */
object Money {
    const val STORAGE_SCALE = 4
    private const val MAX_INTEGER_DIGITS = 15
    val ZERO: BigDecimal = BigDecimal.ZERO.setScale(STORAGE_SCALE)

    fun amount(value: BigDecimal, currency: Currency): BigDecimal {
        require(value.stripTrailingZeros().scale() <= currency.fractionDigits) {
            "${currency.name} amount may have at most ${currency.fractionDigits} decimal places, was $value"
        }

        return calculated(value, currency)
    }

    fun calculated(value: BigDecimal, currency: Currency): BigDecimal {
        val rounded = value.setScale(currency.fractionDigits, RoundingMode.HALF_EVEN)
            .setScale(STORAGE_SCALE)
        require(rounded.precision() - rounded.scale() <= MAX_INTEGER_DIGITS) {
            "amount is too large, was $value"
        }
        return rounded
    }

    fun isZero(value: BigDecimal): Boolean = value.signum() == 0
}
