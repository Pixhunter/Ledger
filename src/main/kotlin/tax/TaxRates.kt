package org.example.tax

import org.example.model.enums.Currency
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class TaxRate(
    val country: String,
    val currency: Currency,
    val validFrom: LocalDate,
    val rate: BasisPoints,
    val legalReference: String,
)

/** Selects the rate using the PSP payment time, so law changes never reprice old payments. */
class TaxRates(private val rates: List<TaxRate> = EU_STANDARD_HISTORY) {
    fun lookup(country: String, currency: Currency, at: Instant): TaxRate? {
        val paymentDate = at.atZone(ZoneOffset.UTC).toLocalDate()
        return rates.asSequence()
            .filter { it.country.equals(country, true) && it.currency == currency && it.validFrom <= paymentDate }
            .maxByOrNull { it.validFrom }
    }

    fun supported(): Set<String> = rates.mapTo(mutableSetOf()) { it.country }

    companion object {
        private val HISTORY_START = LocalDate.of(2021, 1, 1)
        private const val EC_2021 = "European Commission VAT rates, 1 January 2021"

        private fun rate(country: String, bps: Int, from: LocalDate = HISTORY_START, law: String = EC_2021) =
            TaxRate(country, Currency.EUR, from, BasisPoints(bps), law)

        val EU_STANDARD_HISTORY: List<TaxRate> = listOf(
            rate("AT", 2000), rate("BE", 2100), rate("BG", 2000), rate("CY", 1900),
            rate("CZ", 2100), rate("DE", 1900), rate("DK", 2500), rate("EE", 2000),
            rate("EE", 2200, LocalDate.of(2024, 1, 1), "Estonian VAT Act amendment RT I, 01.07.2023, 2"),
            rate("EE", 2400, LocalDate.of(2025, 7, 1), "Estonian VAT Act amendment RT I, 02.01.2025, 2"),
            rate("ES", 2100), rate("FI", 2400),
            rate("FI", 2550, LocalDate.of(2024, 9, 1), "Finnish Act 706/2024"),
            rate("FR", 2000), rate("GR", 2400), rate("HR", 2500), rate("HU", 2700),
            rate("IE", 2300), rate("IT", 2200), rate("LT", 2100), rate("LU", 1700),
            rate("LU", 1600, LocalDate.of(2023, 1, 1), "Luxembourg law of 26 October 2022"),
            rate("LU", 1700, LocalDate.of(2024, 1, 1), "Luxembourg law of 26 October 2022 (temporary rate ended)"),
            rate("LV", 2100), rate("MT", 1800), rate("NL", 2100), rate("PL", 2300),
            rate("PT", 2300), rate("RO", 1900),
            rate("RO", 2100, LocalDate.of(2025, 8, 1), "Romanian Law 141/2025"),
            rate("SE", 2500), rate("SI", 2200), rate("SK", 2000),
            rate("SK", 2300, LocalDate.of(2025, 1, 1), "Slovak Act 278/2024"),
        )
    }
}
