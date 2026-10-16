package cn.market.assistant.data.portfolio

import cn.market.assistant.Ledger
import cn.market.assistant.domain.portfolio.PeriodFacts
import cn.market.assistant.domain.portfolio.PortfolioAnalytics
import cn.market.assistant.domain.portfolio.PortfolioReportRepository
import cn.market.assistant.domain.portfolio.ReportPeriod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Adapts the local ledger to the pure domain report engine. */
class LocalPortfolioReportRepository(
    private val ledger: Ledger,
    private val ioDispatcher: CoroutineDispatcher
) : PortfolioReportRepository {
    override suspend fun facts(period: ReportPeriod, asOf: LocalDate): PeriodFacts = withContext(ioDispatcher) {
        PortfolioAnalytics.facts(
            period = period,
            asOf = asOf,
            snapshots = ledger.portfolioSnapshots(),
            positionsByDay = ledger.positionSnapshots(),
            trades = ledger.trades(),
            cashEvents = ledger.accountCashEvents()
        )
    }
}
