package cn.market.assistant

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class DatePickerDatesTest {
    @Test
    fun roundTripsTheSelectedCalendarDay() {
        val day = LocalDate.parse("2026-10-10")
        assertEquals(day, dateFromPickerMillis(datePickerMillis(day)))
    }

    @Test
    fun usesUtcMidnightAsThePickerContract() {
        assertEquals(
            datePickerMillis(LocalDate.parse("2026-01-01")),
            LocalDate.parse("2026-01-01").atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        )
    }
}
