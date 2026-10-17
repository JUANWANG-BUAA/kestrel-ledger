package cn.market.assistant

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Material3 DatePicker uses UTC midnight for its selected millis; keep the conversion explicit. */
internal fun datePickerMillis(day: LocalDate): Long =
    day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun dateFromPickerMillis(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
