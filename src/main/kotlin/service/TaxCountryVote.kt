package org.example.service

import org.example.utils.logger


object TaxCountryVote {

    private val log = logger<TaxCountryVote>()

    sealed interface Result {
        data class Decided(val country: String, val agreeing: Int) : Result
        data class Insufficient(val evidence: Map<String, String?>) : Result
    }

    fun decide(
        billingCountry: String?,
        cardIssuingCountry: String?,
        ipCountry: String?,
    ): Result {
        val evidence = mapOf(
            "billing" to billingCountry,
            "card" to cardIssuingCountry,
            "ip" to ipCountry,
        )

        val votes = evidence.values.filterNotNull()
        if (votes.isEmpty()) return Result.Insufficient(evidence)

        val counts = votes.groupingBy { it }.eachCount()
        val best = counts.maxByOrNull { it.value }!!

        if (best.value >= 2) {
            if (counts.size > 1) {
                log.warn(
                    "event=tax_country outcome=conflict country={} agreeing={}/{}",
                    best.key, best.value, votes.size,
                )
                log.debug("tax country signals: {}", evidence)
            }
            return Result.Decided(best.key, best.value)
        }

        // TODO Replace this permissive fallback with stronger evidence validation when
        // tax-jurisdiction verification enters scope.
        billingCountry?.let {
            log.warn(
                "event=tax_country outcome=weak_evidence country={} agreeing=1/{}",
                it, votes.size,
            )
            log.debug("tax country signals: {}", evidence)
            return Result.Decided(it, 1)
        }

        return Result.Insufficient(evidence)
    }
}