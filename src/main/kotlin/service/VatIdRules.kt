package org.example.service

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.example.utils.Constants

data class VatIdRule(
    val country: String,
    val validFrom: LocalDate,
    val pattern: Regex,
    val reason: String,
)

class VatIdRules(private val rules: List<VatIdRule> = EU_RULES) {
    fun isValid(country: String?, vatId: String, at: Instant): Boolean {
        if (country == null) return false
        val date = at.atZone(ZoneOffset.UTC).toLocalDate()
        val rule = rules.asSequence()
            .filter { it.country.equals(country, true) && it.validFrom <= date }
            .maxByOrNull { it.validFrom }
            ?: return false
        val normalized = vatId.uppercase().replace(" ", "").replace("-", "")
        return rule.pattern.matches(normalized)
    }

    companion object {
        private val START = Constants.Sources.VALID_FROM
        private val BASELINE = Constants.Sources.EU_VAT_ID_BASELINE
        private fun rule(country: String, body: String) = VatIdRule(country, START, Regex("^$country$body$"), BASELINE)

        val EU_RULES = listOf(
            rule("AT", "U\\d{8}"), rule("BE", "\\d{10}"), rule("BG", "\\d{9,10}"),
            rule("CY", "\\d{8}[A-Z]"), rule("CZ", "\\d{8,10}"), rule("DE", "\\d{9}"),
            rule("DK", "\\d{8}"), rule("EE", "\\d{9}"), rule("ES", "[A-Z0-9]\\d{7}[A-Z0-9]"),
            rule("FI", "\\d{8}"), rule("FR", "[A-Z0-9]{2}\\d{9}"), rule("GR", "\\d{9}"),
            rule("HR", "\\d{11}"), rule("HU", "\\d{8}"),
            rule("IE", "\\d[A-Z0-9+*]\\d{5}[A-Z]{1,2}"), rule("IT", "\\d{11}"),
            rule("LT", "(?:\\d{9}|\\d{12})"), rule("LU", "\\d{8}"), rule("LV", "\\d{11}"),
            rule("MT", "\\d{8}"), rule("NL", "\\d{9}B\\d{2}"), rule("PL", "\\d{10}"),
            rule("PT", "\\d{9}"), rule("RO", "\\d{2,10}"), rule("SE", "\\d{12}"),
            rule("SI", "\\d{8}"), rule("SK", "\\d{10}"),
        )
    }
}
