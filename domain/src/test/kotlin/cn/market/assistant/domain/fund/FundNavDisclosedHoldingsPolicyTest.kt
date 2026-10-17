package cn.market.assistant.domain.fund

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FundNavDisclosedHoldingsPolicyTest {
    @Test
    fun backfilledHoldingsAreNeverHighConfidence() {
        assertEquals(FundEstimateGrade.LOW, FundNavEstimator.disclosedHoldingsGradeForBackfill())
    }

    @Test
    fun lookaheadReasonIsExplicit() {
        assertTrue(FundNavEstimator.DISCLOSED_HOLDINGS_LOOKAHEAD_REASON.contains("前视偏差"))
        assertTrue(FundNavEstimator.DISCLOSED_HOLDINGS_LOOKAHEAD_REASON.contains("低置信"))
    }
}
