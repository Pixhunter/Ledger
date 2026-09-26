package org.example.tax

import org.slf4j.LoggerFactory

object TaxCountryVote {

    private val log = LoggerFactory.getLogger(TaxCountryVote::class.java)

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
                log.warn("tax country conflict, {} wins with {} votes: {}", best.key, best.value, evidence)
            }
            return Result.Decided(best.key, best.value)
        }

        // TODO Replace this permissive fallback with stronger evidence validation when
        // tax-jurisdiction verification enters scope.
        billingCountry?.let {
            log.warn("no majority, falling back to billing country {}: {}", it, evidence)
            return Result.Decided(it, 1)
        }

        return Result.Insufficient(evidence)
    }
}
