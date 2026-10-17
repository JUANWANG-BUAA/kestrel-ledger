package cn.market.assistant.data.assistant

import cn.market.assistant.Ledger
import cn.market.assistant.data.portfolio.LocalPortfolioReportRepository
import cn.market.assistant.domain.assistant.AgentToolRegistry
import cn.market.assistant.domain.market.MarketRepository
import cn.market.assistant.domain.fund.FundRepository
import kotlinx.coroutines.CoroutineDispatcher

/** Composition point for read tools and user-confirmed local-change proposals. */
object ReadOnlyAgentTools {
    fun createRegistry(
        ledger: Ledger,
        marketRepository: MarketRepository,
        ioDispatcher: CoroutineDispatcher,
        fundRepository: FundRepository
    ): AgentToolRegistry = AgentToolRegistry(
        listOf(
            MarketQuoteAgentTool(marketRepository),
            PortfolioPeriodReportAgentTool(LocalPortfolioReportRepository(ledger, ioDispatcher)),
            LocalPortfolioAgentTool(ledger),
            FundCatalogAgentTool(fundRepository),
            PortfolioChangeProposalAgentTool(ledger),
            HttpFetchAgentTool()
        )
    )
}
