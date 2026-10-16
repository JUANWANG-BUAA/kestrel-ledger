package cn.market.assistant.domain.portfolio

import java.time.LocalDate

/** Persistence boundary for point-in-time portfolio snapshots. */
interface PortfolioSnapshotRepository {
    fun capture(day: LocalDate): PortfolioSnapshot
}
