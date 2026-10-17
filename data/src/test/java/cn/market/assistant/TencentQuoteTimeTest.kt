package cn.market.assistant

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TencentQuoteTimeTest {
    @Test
    fun parsesSourceQuoteTimeAndFallsBackWhenMissing() {
        val expected = LocalDateTime.parse("2026-10-16T16:14:51")
            .atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond()

        assertEquals(expected, parseTencentQuoteTime("20261016161451", 0L))
        assertEquals(42L, parseTencentQuoteTime("unavailable", 42L))
    }
}
