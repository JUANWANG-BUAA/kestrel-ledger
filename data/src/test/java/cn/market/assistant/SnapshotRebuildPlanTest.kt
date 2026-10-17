package cn.market.assistant

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SnapshotRebuildPlanTest {
    @Test
    fun includesBothEndpoints() {
        assertEquals(
            listOf(LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-17")),
            snapshotRebuildDays(LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-17"))
        )
    }

    @Test
    fun supportsASingleDay() {
        val day = LocalDate.parse("2026-10-17")
        assertEquals(listOf(day), snapshotRebuildDays(day, day))
    }

    @Test
    fun rejectsReversedRanges() {
        assertThrows(IllegalArgumentException::class.java) {
            snapshotRebuildDays(LocalDate.parse("2026-10-18"), LocalDate.parse("2026-10-17"))
        }
    }
}
