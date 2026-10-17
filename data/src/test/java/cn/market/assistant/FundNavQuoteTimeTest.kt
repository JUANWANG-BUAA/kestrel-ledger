package cn.market.assistant

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FundNavQuoteTimeTest {
    @Test
    fun mapsThePublishedDayToShanghaiClose() {
        val expected = LocalDateTime.parse("2026-10-16T15:00:00")
            .atZone(ZoneId.of("Asia/Shanghai")).toInstant()
        assertEquals(expected, fundNavQuoteObservedAt("2026-10-16"))
    }

    @Test
    fun rejectsMalformedDays() {
        assertNull(fundNavQuoteObservedAt("2026-13-40"))
        assertNull(fundNavQuoteObservedAt(""))
    }
}
