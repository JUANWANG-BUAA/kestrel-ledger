package cn.market.assistant.domain.portfolio

import java.time.LocalDate

/** Read-only access to deterministic daily, weekly and monthly portfolio facts. */
fun interface PortfolioReportRepository {
    suspend fun facts(period: ReportPeriod, asOf: LocalDate): PeriodFacts
}
