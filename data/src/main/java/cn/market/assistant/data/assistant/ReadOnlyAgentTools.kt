package cn.market.assistant.data.assistant

import cn.market.assistant.Ledger
import cn.market.assistant.data.portfolio.LocalPortfolioReportRepository
import cn.market.assistant.domain.assistant.AgentToolRegistry
import cn.market.assistant.domain.market.MarketRepository
import kotlinx.coroutines.CoroutineDispatcher

/** Composition point for the built-in, read-only market and portfolio tools. */
object ReadOnlyAgentTools {
    fun createRegistry(
        ledger: Ledger,
        marketRepository: MarketRepository,
        ioDispatcher: CoroutineDispatcher
    ): AgentToolRegistry = AgentToolRegistry(
        listOf(
            MarketQuoteAgentTool(marketRepository),
            PortfolioPeriodReportAgentTool(LocalPortfolioReportRepository(ledger, ioDispatcher)),
            LocalPortfolioAgentTool(ledger),
            HttpFetchAgentTool()
        )
    )
}
