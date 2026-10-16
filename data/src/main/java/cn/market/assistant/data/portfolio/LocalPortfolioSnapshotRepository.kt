package cn.market.assistant.data.portfolio

import cn.market.assistant.Ledger
import cn.market.assistant.domain.portfolio.PortfolioSnapshot
import cn.market.assistant.domain.portfolio.PortfolioSnapshotRepository
import java.time.LocalDate

/** Captures the current local ledger without reaching into UI state or a remote service. */
class LocalPortfolioSnapshotRepository(
    private val ledger: Ledger
) : PortfolioSnapshotRepository {
    override fun capture(day: LocalDate): PortfolioSnapshot {
        ledger.savePortfolioSnapshot(ledger.positions(), day)
        return ledger.portfolioSnapshots().lastOrNull { it.day == day && it.valid }
            ?: error("Portfolio snapshot was not persisted for $day")
    }
}
