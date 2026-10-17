package cn.market.assistant

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The published NAV belongs to the Shanghai trading day; use the 15:00 close as its observation. */
internal fun fundNavQuoteObservedAt(day: String): Instant? = runCatching {
    LocalDate.parse(day).atTime(15, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant()
}.getOrNull()
