package cn.market.assistant.domain.fund

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FundNavFreshnessTest {
    @Test
    fun acceptsBaseNavUpToFourteenDaysOld() {
        assertTrue(FundNavEstimator.isBaseNavFresh("2026-10-01", "2026-10-15"))
        assertEquals(14L, FundNavEstimator.baseNavAgeDays("2026-10-01", "2026-10-15"))
    }

    @Test
    fun rejectsStaleOrFutureBaseNav() {
        assertFalse(FundNavEstimator.isBaseNavFresh("2026-09-30", "2026-10-15"))
        assertFalse(FundNavEstimator.isBaseNavFresh("2026-10-16", "2026-10-15"))
        assertNull(FundNavEstimator.baseNavAgeDays("not-a-day", "2026-10-15"))
        assertFalse(FundNavEstimator.isBaseNavFresh("not-a-day", "2026-10-15"))
    }
}
