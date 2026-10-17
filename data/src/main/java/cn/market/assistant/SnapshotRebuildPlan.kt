package cn.market.assistant

import java.time.LocalDate

/** Inclusive calendar-day range used by the snapshot rebuild; weekends are intentional observations. */
internal fun snapshotRebuildDays(from: LocalDate, through: LocalDate): List<LocalDate> {
    require(!from.isAfter(through)) { "重建起始日不能晚于结束日" }
    return generateSequence(from) { it.plusDays(1) }
        .takeWhile { !it.isAfter(through) }
        .toList()
}
