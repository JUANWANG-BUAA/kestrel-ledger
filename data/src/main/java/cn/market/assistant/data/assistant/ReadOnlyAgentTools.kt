package cn.market.assistant.data.assistant

import android.content.Context
import cn.market.assistant.Ledger
import cn.market.assistant.data.portfolio.LocalPortfolioReportRepository
import cn.market.assistant.domain.assistant.AgentToolRegistry
import cn.market.assistant.domain.market.MarketRepository
import cn.market.assistant.domain.fund.FundRepository
import kotlinx.coroutines.CoroutineDispatcher

/** Composition point for read tools, saved recipes and user-confirmed local-change proposals. */
object ReadOnlyAgentTools {
    fun createRegistry(
        context: Context,
        ledger: Ledger,
        marketRepository: MarketRepository,
        ioDispatcher: CoroutineDispatcher,
        fundRepository: FundRepository
    ): AgentToolRegistry {
        val handle = RecipeRegistryHandle()
        val workspace = LocalAssistantWorkspace(context)
        val registry = AgentToolRegistry(
            listOf(
                MarketQuoteAgentTool(marketRepository),
                MarketChartAgentTool(ledger),
                PortfolioPeriodReportAgentTool(LocalPortfolioReportRepository(ledger, ioDispatcher)),
                LocalPortfolioAgentTool(ledger),
                PortfolioRiskAgentTool(ledger),
                AlertsAgentTool(ledger),
                FundCatalogAgentTool(fundRepository),
                PortfolioChangeProposalAgentTool(ledger),
                RecipeAgentTool(ledger, workspace, handle),
                WorkspaceListTool(workspace),
                WorkspaceReadTool(workspace),
                WorkspaceWriteTool(workspace),
                WorkspaceQueryTool(workspace),
                HttpFetchAgentTool()
            )
        )
        handle.registry = registry
        return registry
    }
}
