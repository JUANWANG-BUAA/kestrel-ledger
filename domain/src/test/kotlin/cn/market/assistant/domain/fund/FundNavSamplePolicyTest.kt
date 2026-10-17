package cn.market.assistant.domain.fund

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FundNavSamplePolicyTest {
    @Test
    fun requiresOneHundredTwentyAlignedObservations() {
        assertFalse(FundNavEstimator.hasSufficientEstimationHistory(119))
        assertTrue(FundNavEstimator.hasSufficientEstimationHistory(120))
        assertTrue(FundNavEstimator.hasSufficientEstimationHistory(180))
    }
}
