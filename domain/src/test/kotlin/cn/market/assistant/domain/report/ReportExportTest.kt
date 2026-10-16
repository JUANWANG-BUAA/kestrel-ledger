package cn.market.assistant.domain.report

import cn.market.assistant.domain.portfolio.AllocationKind
import cn.market.assistant.domain.portfolio.PeriodContribution
import cn.market.assistant.domain.portfolio.PeriodFacts
import cn.market.assistant.domain.portfolio.PortfolioAllocation
import cn.market.assistant.domain.portfolio.PortfolioSnapshot
import cn.market.assistant.domain.portfolio.ReportPeriod
import cn.market.assistant.domain.portfolio.XirrResult
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportExportTest {
    private val facts = PeriodFacts(
        period = ReportPeriod.WEEK,
        start = LocalDate.parse("2026-10-12"),
        end = LocalDate.parse("2026-10-18"),
        opening = snapshot("2026-10-12", "10000"),
        closing = snapshot("2026-10-18", "10300"),
        profit = BigDecimal("200"),
        modifiedDietzPct = BigDecimal("2.04"),
        transactionCount = 3,
        buyAmount = BigDecimal("500"),
        sellAmount = BigDecimal("0"),
        missingReason = null,
        contributors = listOf(
            PeriodContribution("甲基金", BigDecimal("150"), BigDecimal("75")),
            PeriodContribution("乙基金", BigDecimal("50"), BigDecimal("25"))
        ),
        accountProfit = BigDecimal("200"),
        accountModifiedDietzPct = BigDecimal("2.04"),
        accountTwrPct = BigDecimal("2.10"),
        accountXirr = XirrResult.Available(0.105),
        accountMaxDrawdownPct = BigDecimal("3.20"),
        accountMaxDrawdownPeakDay = LocalDate.parse("2026-10-14"),
        accountMaxDrawdownTroughDay = LocalDate.parse("2026-10-16"),
        accountMaxDrawdownObservationCount = 6,
        accountAllocations = listOf(
            PortfolioAllocation("000001", "甲基金", AllocationKind.FUND, BigDecimal("8000"), BigDecimal("77.67"))
        ),
        benchmarkReturnPct = BigDecimal("1.10"),
        benchmarkName = "沪深300",
        benchmarkSource = "eastmoney",
        accountExcessReturnPct = BigDecimal("1.00"),
        transactionFeeAmount = BigDecimal("12.50"),
        dividendWithholdingTax = BigDecimal("3.00")
    )
    private val provenance = ReportProvenance(
        generatedAtMillis = 1_763_000_000_000,
        dataSources = listOf("eastmoney", "sge-au9999", "eastmoney"),
        appVersion = "0.1.0"
    )

    @Test
    fun markdownCarriesFactsSourcesAndTheRedLines() {
        val markdown = ReportExport.periodFactsMarkdown(facts, provenance)

        assertTrue(markdown.contains("# 养基助手 · 本周事实报告"))
        assertTrue(markdown.contains("2026-10-12 ~ 2026-10-18"))
        assertTrue(markdown.contains("数据来源：eastmoney、sge-au9999"))
        assertTrue(markdown.contains("| 金额盈亏 | ¥200.00 |"))
        assertTrue(markdown.contains("| 账户 TWR | +2.10% |"))
        assertTrue(markdown.contains("| 账户 XIRR（年化） | +10.50% |"))
        assertTrue(markdown.contains("| 最大回撤 | 3.20% | 2026-10-14 → 2026-10-16 |"))
        assertTrue(markdown.contains("| 甲基金 | ¥150.00 | +75.00% |"))
        assertTrue(markdown.contains("沪深300 价格收益：+1.10%（来源 eastmoney）"))
        assertTrue(markdown.contains("本报告由本机账本与已缓存公开数据生成"))
        assertTrue(markdown.contains("不构成投资建议"))
        assertTrue(markdown.contains("所有估值均为估算"))
    }

    @Test
    fun missingMetricsPrintADashTogetherWithTheReason() {
        val incomplete = facts.copy(
            accountTwrPct = null,
            accountTwrMissingReason = "连续有效日收益不足",
            accountXirr = XirrResult.Unavailable(
                cn.market.assistant.domain.portfolio.XirrFailureReason.INCOMPLETE_VALUATION
            ),
            accountMaxDrawdownMissingReason = "缺少最大回撤基准快照"
        )

        val markdown = ReportExport.periodFactsMarkdown(incomplete, provenance)

        assertTrue(markdown.contains("| 账户 TWR | — | 连续有效日收益不足 |"))
        assertTrue(markdown.contains("资金流估值边界不完整，未计算"))
        assertTrue(markdown.contains("## 数据完整性"))
        assertTrue(markdown.contains("- 缺少最大回撤基准快照"))
    }

    @Test
    fun csvIsLongFormatAndEscapesSeparators() {
        val csv = ReportExport.periodFactsCsv(facts, provenance)
        val lines = csv.trim().split("\n")

        assertEquals("section,metric,value,unit,note", lines.first())
        assertTrue(lines.any { it == "account,buy_amount,500,CNY," })
        assertTrue(lines.any { it.startsWith("contribution,甲基金,150,CNY,75") })
        assertTrue(lines.any { it == "allocation,甲基金,8000,CNY,77.67%" })
        assertEquals("\"a,b\"", ReportExport.csvEscape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", ReportExport.csvEscape("say \"hi\""))
        assertEquals("line one line two", ReportExport.csvEscape("line one\nline two"))
        assertFalse(ReportExport.csv(listOf(listOf("a"), listOf("b"))).contains("\r"))
    }

    private fun snapshot(day: String, total: String): PortfolioSnapshot {
        val value = BigDecimal(total)
        return PortfolioSnapshot(
            day = LocalDate.parse(day),
            observedAt = "${day}T16:00:00Z",
            positionCount = 1,
            pricedPositionCount = 1,
            marketValue = value,
            cost = value,
            holdingProfit = BigDecimal.ZERO,
            realizedProfit = BigDecimal.ZERO,
            totalProfit = BigDecimal.ZERO,
            cashBalance = BigDecimal.ZERO,
            totalValue = value,
            externalNetFlow = BigDecimal.ZERO,
            valuationFresh = true
        )
    }
}
