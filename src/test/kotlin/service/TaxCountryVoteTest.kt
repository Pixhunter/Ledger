package org.example.service

import org.example.service.TaxCountryVote.Result.Decided
import kotlin.test.Test
import kotlin.test.assertEquals

class TaxCountryVoteTest {

    @Test
    fun `all three signals agree`() {
        val result = TaxCountryVote.decide(
            billingCountry = "ES",
            cardIssuingCountry = "ES",
            ipCountry = "ES",
        )

        assertEquals(Decided("ES", 3), result)
    }

    @Test
    fun `two of three signals agree`() {
        val result = TaxCountryVote.decide(
            billingCountry = "ES",
            cardIssuingCountry = "AU",
            ipCountry = "ES",
        )

        assertEquals(Decided("ES", 2), result)
    }

    @Test
    fun `majority wins over billing country`() {
        val result = TaxCountryVote.decide(
            billingCountry = "AU",
            cardIssuingCountry = "AU",
            ipCountry = "ES",
        )

        assertEquals(Decided("AU", 2), result)
    }

    @Test
    fun `no majority falls back to billing country`() {
        val result = TaxCountryVote.decide(
            billingCountry = "ES",
            cardIssuingCountry = "AU",
            ipCountry = "FR",
        )

        assertEquals(Decided("ES", 1), result)
    }
}
