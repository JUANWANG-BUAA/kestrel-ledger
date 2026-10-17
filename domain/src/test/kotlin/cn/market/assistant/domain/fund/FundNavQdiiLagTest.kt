package cn.market.assistant.domain.fund

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FundNavQdiiLagTest {
    @Test
    fun shiftsAlongTradingSessionsNotCalendarDays() {
        val returns = mapOf(
            "2026-10-09" to 1.0, // Friday
            "2026-10-12" to 2.0, // Monday
            "2026-10-13" to 3.0
        )
        val shifted = FundNavEstimator.shiftReturnsByTradingSessions(returns, 1)
        assertEquals(1.0, shifted["2026-10-12"]!!, 0.0)
        assertEquals(2.0, shifted["2026-10-13"]!!, 0.0)
        assertFalse(shifted.containsKey("2026-10-10"))
        assertFalse(shifted.containsKey("2026-10-09"))
    }

    @Test
    fun zeroLagLeavesReturnsUnchanged() {
        val returns = mapOf("2026-10-09" to 1.0)
        assertEquals(returns, FundNavEstimator.shiftReturnsByTradingSessions(returns, 0))
    }

    @Test
    fun lagLongerThanAvailableSessionsDropsTheTail() {
        val shifted = FundNavEstimator.shiftReturnsByTradingSessions(
            mapOf("2026-10-09" to 1.0, "2026-10-12" to 2.0),
            lagDays = 3
        )
        assertEquals(emptyMap<String, Double>(), shifted)
    }
}
