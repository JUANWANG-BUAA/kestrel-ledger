package cn.market.assistant.domain.fund

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoldPremiumModelTest {
    @Test
    fun fairValueConvertsUsdPerOunceIntoYuansPerGram() {
        val value = GoldPremiumModel.fairValueCnyPerGram(2_000.0, 7.1)

        requireNotNull(value)
        assertEquals(2_000.0 * 7.1 / 31.1034768, value, 1e-9)
        assertNull(GoldPremiumModel.fairValueCnyPerGram(0.0, 7.1))
        assertNull(GoldPremiumModel.fairValueCnyPerGram(2_000.0, Double.NaN))
    }

    @Test
    fun rollingPremiumProducesADomesticReferenceWithoutClaimingBankAccuracy() {
        val observations = (0 until 60).map { index ->
            val international = 2_000.0 + index * 1.5
            val usdCny = 7.1
            val fair = GoldPremiumModel.fairValueCnyPerGram(international, usdCny)!!
            GoldPremiumObservation(
                day = java.time.LocalDate.parse("2026-04-01").plusDays(index.toLong()).toString(),
                domesticCnyPerGram = fair * 1.002,
                internationalUsdPerOunce = international,
                usdCny = usdCny
            )
        }

        val fit = GoldPremiumModel.fit(observations, currentInternationalUsdPerOunce = 2_100.0, currentUsdCny = 7.0)

        assertNull(fit.reason)
        assertEquals(0.2, fit.averagePremiumPct!!, 1e-9)
        assertEquals(GoldPremiumModel.fairValueCnyPerGram(2_100.0, 7.0)!! * 1.002, fit.estimatedDomesticCnyPerGram!!, 1e-6)
        assertEquals(GoldPremiumConfidence.CALIBRATED, fit.confidence)
        assertFalse(fit.bankQuoteEligible)
        assertNotNull(fit.residualMaePct)
    }

    @Test
    fun insufficientHistoryReturnsUnavailableInsteadOfANumber() {
        val observations = (0 until 5).map { index ->
            GoldPremiumObservation(
                day = java.time.LocalDate.parse("2026-05-01").plusDays(index.toLong()).toString(),
                domesticCnyPerGram = 500.0,
                internationalUsdPerOunce = 2_100.0,
                usdCny = 7.1
            )
        }

        val fit = GoldPremiumModel.fit(observations, currentInternationalUsdPerOunce = 2_100.0, currentUsdCny = 7.1)

        assertEquals(GoldPremiumConfidence.UNAVAILABLE, fit.confidence)
        assertNull(fit.estimatedDomesticCnyPerGram)
        assertTrue(fit.reason!!.contains("少于"))
    }

    @Test
    fun premiumDetectsADomesticDiscountAndDuplicateDaysAreDropped() {
        val fair = GoldPremiumModel.fairValueCnyPerGram(2_000.0, 7.0)!!
        val duplicate = GoldPremiumObservation("2026-06-01", fair * 0.99, 2_000.0, 7.0)
        val observations = List(30) { duplicate }

        val fit = GoldPremiumModel.fit(observations)

        assertEquals(1, fit.observationCount)
        assertEquals(0, fit.windowDays)
        assertEquals(GoldPremiumConfidence.UNAVAILABLE, fit.confidence)
        assertEquals(-1.0, GoldPremiumModel.premiumPct(fair * 0.99, fair)!!, 1e-9)
    }
}
