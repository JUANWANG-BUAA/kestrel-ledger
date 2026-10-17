package cn.market.assistant.data.assistant

import cn.market.assistant.domain.assistant.AgentToolAuthorization
import cn.market.assistant.domain.assistant.AgentToolRegistry
import cn.market.assistant.domain.assistant.AgentToolRequest
import cn.market.assistant.domain.assistant.FactSensitivity
import cn.market.assistant.domain.market.CachedMarketChart
import cn.market.assistant.domain.market.ChartPeriod
import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.domain.market.DataSourceRun
import cn.market.assistant.domain.market.DailyHistoryCoverage
import cn.market.assistant.domain.market.MarketBoardSnapshot
import cn.market.assistant.domain.market.MarketBoardType
import cn.market.assistant.domain.market.MarketRepository
import cn.market.assistant.domain.market.QuoteRefreshResult
import cn.market.assistant.domain.portfolio.PeriodContribution
import cn.market.assistant.domain.portfolio.PeriodFacts
import cn.market.assistant.domain.portfolio.AllocationKind
import cn.market.assistant.domain.portfolio.PortfolioAllocation
import cn.market.assistant.domain.portfolio.PortfolioAllocationChange
import cn.market.assistant.domain.portfolio.PortfolioReportRepository
import cn.market.assistant.domain.portfolio.ReportPeriod
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class ReadOnlyAgentToolsTest {
    @Test
    fun marketQuoteToolResolvesByNameAndOnlyReadsCachedData() {
        val quote = MarketQuote("100.NDX", "纳斯达克", 20_000.0, 100.0, 0.5, 1_792_000_000, 1_792_000_001, "fixture")
        val repository = FakeMarketRepository(mapOf(quote.secid to quote))
        val tool = MarketQuoteAgentTool(repository)

        val result = runSuspend { tool.execute(AgentToolRequest(mapOf("query" to "纳斯达克"))) }

        assertEquals(null, result.errorCode)
        assertEquals("20000", result.facts.first { it.key == "market.quote.price" }.value)
        assertEquals("fixture", result.facts.first().source)
        assertEquals("2026-10-14T17:46:40Z", result.facts.first().observedAt)
        assertEquals(0, repository.refreshCount)
    }

    @Test
    fun ambiguousMarketSearchReturnsCandidatesWithoutGuessing() {
        val tool = MarketQuoteAgentTool(FakeMarketRepository(emptyMap()))

        val result = runSuspend { tool.execute(AgentToolRequest(mapOf("query" to "指数"))) }

        assertEquals(null, result.errorCode)
        assertTrue(result.facts.size > 1)
        assertTrue(result.facts.all { it.key.startsWith("market.search.candidate.") })
        assertTrue(result.facts.all { !it.source.isNullOrBlank() && !it.observedAt.isNullOrBlank() })
    }

    @Test
    fun internationalGoldToolReturnsOriginalUsdPerTroyOunceQuote() {
        val quote = MarketQuote(
            "GOLD.XAU.USD.OZ", "国际黄金 XAU/USD", 4_150.25, 12.5, 0.3,
            1_792_000_000, 1_792_000_001, "gold-api-xau-usd"
        )
        val tool = MarketQuoteAgentTool(FakeMarketRepository(mapOf(quote.secid to quote)))

        val result = runSuspend { tool.execute(AgentToolRequest(mapOf("query" to "国际金价"))) }

        assertEquals("4150.25", result.facts.first { it.key == "market.quote.price" }.value)
        assertEquals("USD / troy oz", result.facts.first { it.key == "market.quote.price" }.unit)
        assertEquals(quote.source, result.facts.first { it.key == "market.quote.price" }.source)
    }

    @Test
    fun periodReportToolUsesSharedAnalyticsAndKeepsAccountFactsPrivate() {
        val expectedDate = LocalDate.parse("2026-10-16")
        val requestedPeriods = mutableListOf<ReportPeriod>()
        val requestedDates = mutableListOf<LocalDate>()
        val repository = PortfolioReportRepository { period, asOf ->
            requestedPeriods += period
            requestedDates += asOf
            PeriodFacts(
                period = period, start = LocalDate.parse("2026-10-12"), end = asOf,
                opening = null, closing = null, profit = BigDecimal("125.50"),
                modifiedDietzPct = BigDecimal("1.25"), transactionCount = 2,
                buyAmount = BigDecimal("500"), sellAmount = BigDecimal("100"), missingReason = null,
                contributors = listOf(PeriodContribution("基金甲", BigDecimal("125.50"))),
                accountProfit = BigDecimal("100.00"), accountModifiedDietzPct = BigDecimal("1.00"),
                accountMissingReason = null, accountTwrPct = BigDecimal("0.98"), accountTwrMissingReason = null,
                accountMaxDrawdownPct = BigDecimal("2.50"),
                accountMaxDrawdownPeakDay = LocalDate.parse("2026-10-13"),
                accountMaxDrawdownTroughDay = LocalDate.parse("2026-10-15"),
                accountMaxDrawdownObservationCount = 5,
                accountAllocations = listOf(
                    PortfolioAllocation("FUND_A", "基金甲", AllocationKind.FUND, BigDecimal("400"), BigDecimal("40.0000")),
                    PortfolioAllocation("ACCOUNT_CASH", "账户现金", AllocationKind.CASH, BigDecimal("600"), BigDecimal("60.0000"))
                ),
                accountAllocationChanges = listOf(PortfolioAllocationChange("FUND_A", "基金甲", BigDecimal("-10.00"))),
                accountAllocationHhi = BigDecimal("0.520000"),
                accountEffectiveAssetCount = BigDecimal("1.92")
            )
        }
        val tool = PortfolioPeriodReportAgentTool(repository)

        val result = runSuspend {
            tool.execute(AgentToolRequest(mapOf("period" to "week", "as_of" to expectedDate.toString())))
        }
        val registry = AgentToolRegistry(listOf(tool))
        val defaultVisible = runSuspend {
            registry.execute(tool.id, AgentToolRequest(mapOf("period" to "week")), AgentToolAuthorization(setOf(tool.id)))
        }
        val shared = runSuspend {
            registry.execute(
                tool.id, AgentToolRequest(mapOf("period" to "week")),
                AgentToolAuthorization(setOf(tool.id), sharePrivateDetails = true)
            )
        }

        assertEquals(ReportPeriod.WEEK, requestedPeriods.first())
        assertEquals(expectedDate, requestedDates.first())
        assertEquals("125.50", result.facts.first { it.key == "portfolio.report.holdings_profit" }.value)
        assertEquals("2.50", result.facts.first { it.key == "portfolio.report.account_max_drawdown_pct" }.value)
        assertEquals("0.520000", result.facts.first { it.key == "portfolio.report.account_allocation_hhi" }.value)
        assertEquals("40.0000", result.facts.first { it.key == "portfolio.report.allocation.FUND_A.weight_pct" }.value)
        assertEquals("-10.00", result.facts.first { it.key == "portfolio.report.allocation_change.FUND_A_percentage_points" }.value)
        assertTrue(result.facts.all { it.sensitivity == FactSensitivity.PRIVATE_DETAIL })
        assertTrue(defaultVisible.facts.isEmpty())
        assertTrue(shared.facts.isNotEmpty())
    }

    @Test
    fun periodReportToolRejectsUnknownPeriod() {
        val tool = PortfolioPeriodReportAgentTool(PortfolioReportRepository { _, _ -> error("Must not run") })

        val result = runSuspend { tool.execute(AgentToolRequest(mapOf("period" to "quarter"))) }

        assertEquals("INVALID_REPORT_PERIOD", result.errorCode)
    }

    private class FakeMarketRepository(private val quotes: Map<String, MarketQuote>) : MarketRepository {
        var refreshCount: Int = 0
            private set

        override suspend fun cachedQuotes() = quotes
        override suspend fun dataSourceRuns(limit: Int) = emptyList<DataSourceRun>()
        override suspend fun dailyHistoryCoverage() = emptyList<DailyHistoryCoverage>()
        override suspend fun refreshOverview(): QuoteRefreshResult { refreshCount++; error("refresh not expected") }
        override suspend fun refreshGold(): QuoteRefreshResult { refreshCount++; error("refresh not expected") }
        override suspend fun refreshQuote(secid: String): MarketQuote { refreshCount++; error("refresh not expected") }
        override suspend fun cachedMarketBoards(type: MarketBoardType): MarketBoardSnapshot? = null
        override suspend fun refreshMarketBoards(type: MarketBoardType): MarketBoardSnapshot { refreshCount++; error("refresh not expected") }
        override suspend fun cachedChart(secid: String, period: ChartPeriod) = CachedMarketChart(emptyList(), 0L)
        override suspend fun refreshChart(secid: String, period: ChartPeriod) = CachedMarketChart(emptyList(), 0L)
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(outcome: Result<T>) { result = outcome }
        })
        return checkNotNull(result).getOrThrow()
    }
}
