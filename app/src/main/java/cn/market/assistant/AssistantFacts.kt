package cn.market.assistant

import cn.market.assistant.domain.assistant.AssistantFact
import cn.market.assistant.domain.assistant.FactSensitivity
import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.domain.portfolio.AccountCashBalance
import cn.market.assistant.domain.portfolio.PeriodFacts
import cn.market.assistant.domain.portfolio.Position
import cn.market.assistant.domain.portfolio.ReportPeriod
import cn.market.assistant.domain.portfolio.XirrResult
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Converts the same local DTOs the screens already show into typed assistant facts. This keeps the
 * assistant honest: it can only narrate values that exist in the encrypted ledger or the cached
 * public market data, with their source and observation time attached.
 *
 * Key convention: `portfolio.report.<period>.*` for period facts, `portfolio.holding.*` for
 * position-level facts (PRIVATE_DETAIL) and `market.quote.<secid>.*` for public quotes.
 */
object AssistantFacts {
    const val MAX_QUOTES = 12
    private val ROUNDING = RoundingMode.HALF_UP

    fun build(
        reports: List<PeriodFacts>,
        quotes: Map<String, MarketQuote>,
        positions: List<Position>,
        cashBalance: AccountCashBalance?,
        nowMillis: Long = System.currentTimeMillis()
    ): List<AssistantFact> = buildList {
        reports.forEach { report -> addReportFacts(this, report, cashBalance, nowMillis) }
        positions.asSequence()
            .filter { it.units.signum() > 0 }
            .take(20)
            .forEach { position -> addPositionFacts(this, position) }
        quotes.values.asSequence()
            .sortedBy { it.secid }
            .take(MAX_QUOTES)
            .forEach { quote -> addQuoteFacts(this, quote) }
    }

    private fun addReportFacts(
        facts: MutableList<AssistantFact>,
        report: PeriodFacts,
        cashBalance: AccountCashBalance?,
        nowMillis: Long
    ) {
        val period = report.period.name.lowercase()
        val prefix = "portfolio.report.$period"
        val observedAt = report.closing?.observedAt ?: Instant.ofEpochMilli(nowMillis).toString()
        fun aggregate(key: String, value: BigDecimal?, unit: String) {
            if (value == null) return
            facts += AssistantFact(
                key = "$prefix.$key",
                value = value.setScale(2, ROUNDING).toPlainString(),
                unit = unit,
                source = "本地快照与交易流水",
                observedAt = observedAt,
                sensitivity = FactSensitivity.AGGREGATE_PORTFOLIO
            )
        }
        facts += AssistantFact(
            key = "$prefix.range",
            value = "${report.start}~${report.end}",
            unit = null,
            source = "本地快照与交易流水",
            observedAt = observedAt,
            sensitivity = FactSensitivity.AGGREGATE_PORTFOLIO
        )
        aggregate("account_profit", report.accountProfit ?: report.profit, "CNY")
        aggregate("holdings_profit", report.profit, "CNY")
        aggregate("account_twr_pct", report.accountTwrPct, "%")
        aggregate("holdings_modified_dietz_pct", report.modifiedDietzPct, "%")
        aggregate("account_max_drawdown_pct", report.accountMaxDrawdownPct, "%")
        aggregate("cash_balance", cashBalance?.amount, "CNY")
        aggregate("buy_amount", report.buyAmount, "CNY")
        aggregate("sell_amount", report.sellAmount, "CNY")
        aggregate("fees", report.transactionFeeAmount, "CNY")
        aggregate("dividend_withholding_tax", report.dividendWithholdingTax, "CNY")
        when (val xirr = report.accountXirr) {
            is XirrResult.Available -> facts += AssistantFact(
                key = "$prefix.account_xirr_annualized_pct",
                value = BigDecimal.valueOf(xirr.annualizedRate * 100.0).setScale(2, ROUNDING).toPlainString(),
                unit = "% 年化",
                source = "本地快照与交易流水",
                observedAt = observedAt,
                sensitivity = FactSensitivity.AGGREGATE_PORTFOLIO
            )
            is XirrResult.Unavailable -> facts += AssistantFact(
                key = "$prefix.account_xirr_unavailable_reason",
                value = xirr.reason.name,
                unit = null,
                source = "本地快照与交易流水",
                observedAt = observedAt,
                sensitivity = FactSensitivity.AGGREGATE_PORTFOLIO
            )
            null -> Unit
        }
        listOfNotNull(
            report.missingReason,
            report.accountMissingReason,
            report.accountTwrMissingReason,
            report.accountMaxDrawdownMissingReason,
            report.accountAllocationMissingReason,
            report.benchmarkMissingReason
        ).distinct().forEachIndexed { index, reason ->
            facts += AssistantFact(
                key = "$prefix.missing_reason.$index",
                value = reason,
                unit = null,
                source = "本地快照与交易流水",
                observedAt = observedAt,
                sensitivity = FactSensitivity.AGGREGATE_PORTFOLIO
            )
        }
        report.accountAllocations.take(10).forEach { allocation ->
            facts += AssistantFact(
                key = "$prefix.allocation.${allocation.id}.weight_pct",
                value = allocation.weightPct.setScale(2, ROUNDING).toPlainString(),
                unit = "%",
                source = "本地快照与交易流水",
                observedAt = observedAt,
                sensitivity = FactSensitivity.PRIVATE_DETAIL
            )
        }
        report.contributors.take(5).forEachIndexed { index, contributor ->
            facts += AssistantFact(
                key = "$prefix.contributor.${index + 1}",
                value = "${contributor.assetName}: ${contributor.amount.setScale(2, ROUNDING).toPlainString()} CNY",
                unit = null,
                source = "本地快照与交易流水",
                observedAt = observedAt,
                sensitivity = FactSensitivity.PRIVATE_DETAIL
            )
        }
    }

    private fun addPositionFacts(facts: MutableList<AssistantFact>, position: Position) {
        val id = position.asset.symbol
        val observedAt = position.quoteTime ?: Instant.now().toString()
        fun holding(key: String, value: BigDecimal?, unit: String) {
            if (value == null) return
            facts += AssistantFact(
                key = "portfolio.holding.$id.$key",
                value = value.setScale(4, ROUNDING).toPlainString(),
                unit = unit,
                source = position.quoteSource ?: "本地账本",
                observedAt = observedAt,
                sensitivity = FactSensitivity.PRIVATE_DETAIL
            )
        }
        facts += AssistantFact(
            key = "portfolio.holding.$id.name",
            value = position.asset.name,
            unit = null,
            source = position.quoteSource ?: "本地账本",
            observedAt = observedAt,
            sensitivity = FactSensitivity.PRIVATE_DETAIL
        )
        holding("units", position.units, position.asset.category)
        holding("market_value", position.value, "CNY")
        holding("cost", position.cost, "CNY")
        holding("holding_profit", position.holdingProfit, "CNY")
    }

    private fun addQuoteFacts(facts: MutableList<AssistantFact>, quote: MarketQuote) {
        val observedAt = quote.quoteTime.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).toString() }
            ?: Instant.ofEpochMilli(quote.fetchedAt).toString()
        facts += AssistantFact(
            key = "market.quote.${quote.secid}.price",
            value = quote.price.toString(),
            unit = "点位/元",
            source = quote.source,
            observedAt = observedAt,
            sensitivity = FactSensitivity.PUBLIC_MARKET
        )
        facts += AssistantFact(
            key = "market.quote.${quote.secid}.change_pct",
            value = quote.changePct.toString(),
            unit = "%",
            source = quote.source,
            observedAt = observedAt,
            sensitivity = FactSensitivity.PUBLIC_MARKET
        )
    }
}
