package cn.market.assistant.domain.report

import cn.market.assistant.domain.portfolio.PeriodFacts
import cn.market.assistant.domain.portfolio.PortfolioAllocation
import cn.market.assistant.domain.portfolio.XirrFailureReason
import cn.market.assistant.domain.portfolio.XirrResult
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

data class ReportProvenance(
    val generatedAtMillis: Long,
    val dataSources: List<String>,
    val appVersion: String,
    val extraNote: String? = null
)

/**
 * Deterministic, offline export of the same `PeriodFacts` shown on screen. The text never invents a
 * value: a missing metric is written as "—" together with its reason, and the footer repeats the
 * product red lines so a shared report cannot be mistaken for investment advice.
 */
object ReportExport {
    private val marketZone = ZoneId.of("Asia/Shanghai")
    private const val MISSING = "—"

    fun periodFactsMarkdown(facts: PeriodFacts, provenance: ReportProvenance): String = buildString {
        appendLine("# 养基助手 · ${facts.period.title}事实报告")
        appendLine()
        appendLine("- 周期：${facts.start} ~ ${facts.end}（${facts.period.title}）")
        appendLine("- 生成时间：${formatTime(provenance.generatedAtMillis)}（Asia/Shanghai）")
        appendLine("- 应用版本：${provenance.appVersion}")
        appendLine("- 数据来源：${provenance.dataSources.distinct().ifEmpty { listOf("本机账本") }.joinToString("、")}")
        provenance.extraNote?.let { appendLine("- 备注：$it") }
        appendLine()

        appendLine("## 账户事实")
        appendLine("| 指标 | 数值 | 说明 |")
        appendLine("|---|---|---|")
        tableRow("期初总资产", money(facts.opening?.totalValue), facts.opening?.day?.toString() ?: "缺少期初快照")
        tableRow("期末总资产", money(facts.closing?.totalValue), facts.closing?.day?.toString() ?: "缺少期末快照")
        tableRow("期间净投入", money(netExternalFlow(facts)), "期末总资产 − 期初总资产 − 金额盈亏")
        tableRow("金额盈亏", money(facts.profit), facts.missingReason ?: "期末减期初减净投入")
        tableRow("持仓收益率", pct(facts.modifiedDietzPct), facts.missingReason ?: "Modified Dietz")
        tableRow("账户收益率", pct(facts.accountModifiedDietzPct), facts.accountMissingReason ?: "含现金账户口径")
        tableRow("账户 TWR", pct(facts.accountTwrPct), facts.accountTwrMissingReason ?: "外部资金流时点分段连乘")
        tableRow("账户 XIRR（年化）", xirrPct(facts.accountXirr), xirrNote(facts.accountXirr))
        tableRow(
            "最大回撤",
            magnitudePct(facts.accountMaxDrawdownPct),
            facts.accountMaxDrawdownMissingReason ?: run {
                val peak = facts.accountMaxDrawdownPeakDay?.toString() ?: MISSING
                val trough = facts.accountMaxDrawdownTroughDay?.toString() ?: MISSING
                "$peak → $trough"
            }
        )
        tableRow("交易笔数", facts.transactionCount.toString(), "已确认且未冲正")
        tableRow("买入金额", money(facts.buyAmount), "确认流水汇总")
        tableRow("卖出金额", money(facts.sellAmount), "确认流水汇总")
        tableRow("买卖手续费", money(facts.transactionFeeAmount), "事实汇总，不重复扣减收益")
        tableRow("分红代扣税", money(facts.dividendWithholdingTax), "事实汇总")
        appendLine()

        if (facts.contributors.isNotEmpty()) {
            appendLine("## 持仓盈亏贡献")
            appendLine("| 标的 | 金额 | 占净持仓盈亏 |")
            appendLine("|---|---|---|")
            facts.contributors.forEach { contributor ->
                appendLine(
                    "| ${escapePipe(contributor.assetName)} | ${money(contributor.amount)} | " +
                        "${pct(contributor.shareOfNetProfitPct)} |"
                )
            }
            appendLine()
        }

        if (facts.accountAllocations.isNotEmpty()) {
            appendLine("## 期末配置")
            appendLine("| 资产 | 类别 | 市值 | 权重 |")
            appendLine("|---|---|---|---|")
            facts.accountAllocations.forEach { allocation -> appendLine(allocationRow(allocation)) }
            if (facts.accountAllocationHhi != null || facts.accountEffectiveAssetCount != null) {
                appendLine()
                appendLine(
                    "- 集中度 HHI：${facts.accountAllocationHhi?.let { plain(it) } ?: MISSING}；" +
                        "有效资产数：${facts.accountEffectiveAssetCount?.let { plain(it) } ?: MISSING}"
                )
            }
            appendLine()
        }

        if (facts.benchmarkName != null || facts.benchmarkReturnPct != null) {
            appendLine("## 参考基准")
            appendLine(
                "- ${facts.benchmarkName ?: "参考指数"} 价格收益：" +
                    "${pct(facts.benchmarkReturnPct)}" +
                    (facts.benchmarkSource?.let { "（来源 $it）" } ?: "")
            )
            if (facts.accountExcessReturnPct != null) {
                appendLine("- 账户 TWR − 指数价格收益：${pct(facts.accountExcessReturnPct)}（指数不含股息，非个性化基准）")
            }
            facts.benchmarkMissingReason?.let { appendLine("- 暂不可用的原因：$it") }
            appendLine()
        }

        val reasons = listOfNotNull(
            facts.missingReason,
            facts.accountMissingReason,
            facts.accountTwrMissingReason,
            xirrMissingReason(facts.accountXirr),
            facts.accountMaxDrawdownMissingReason,
            facts.accountAllocationMissingReason,
            facts.benchmarkMissingReason
        ).distinct()
        if (reasons.isNotEmpty()) {
            appendLine("## 数据完整性")
            reasons.forEach { appendLine("- $it") }
            appendLine()
        }

        appendLine("---")
        appendLine("本报告由本机账本与已缓存公开数据生成，仅作事实汇总与复盘；不构成投资建议，不含交易通道。")
        appendLine("所有估值均为估算，非基金公司官方净值；行情保留来源与时点，缺失时不填零。")
    }

    /** Long-format CSV so downstream tools can diff two exports row by row. */
    fun periodFactsCsv(facts: PeriodFacts, provenance: ReportProvenance): String {
        val rows = mutableListOf(
            listOf("section", "metric", "value", "unit", "note"),
            listOf("meta", "period", "${facts.start}~${facts.end}", "", facts.period.title),
            listOf("meta", "generated_at", formatTime(provenance.generatedAtMillis), "Asia/Shanghai", provenance.appVersion),
            listOf("meta", "sources", provenance.dataSources.distinct().joinToString("+"), "", "public+cached"),
            listOf("account", "opening_total_value", facts.opening?.totalValue?.toPlainString().orEmpty(), "CNY", ""),
            listOf("account", "closing_total_value", facts.closing?.totalValue?.toPlainString().orEmpty(), "CNY", ""),
            listOf("account", "profit", facts.profit?.toPlainString().orEmpty(), "CNY", facts.missingReason.orEmpty()),
            listOf("account", "holding_return", facts.modifiedDietzPct?.toPlainString().orEmpty(), "%", "Modified Dietz"),
            listOf("account", "account_return", facts.accountModifiedDietzPct?.toPlainString().orEmpty(), "%", ""),
            listOf("account", "account_twr", facts.accountTwrPct?.toPlainString().orEmpty(), "%", ""),
            listOf("account", "account_xirr_annual", xirrRaw(facts.accountXirr), "%", xirrNote(facts.accountXirr)),
            listOf("account", "max_drawdown", facts.accountMaxDrawdownPct?.toPlainString().orEmpty(), "%", ""),
            listOf("account", "transaction_count", facts.transactionCount.toString(), "count", ""),
            listOf("account", "buy_amount", facts.buyAmount.toPlainString(), "CNY", ""),
            listOf("account", "sell_amount", facts.sellAmount.toPlainString(), "CNY", ""),
            listOf("account", "fees", facts.transactionFeeAmount.toPlainString(), "CNY", ""),
            listOf("account", "dividend_withholding_tax", facts.dividendWithholdingTax.toPlainString(), "CNY", "")
        )
        facts.contributors.forEach { contributor ->
            rows += listOf(
                "contribution",
                contributor.assetName,
                contributor.amount.toPlainString(),
                "CNY",
                contributor.shareOfNetProfitPct?.toPlainString().orEmpty()
            )
        }
        facts.accountAllocations.forEach { allocation ->
            rows += listOf(
                "allocation",
                allocation.name,
                allocation.marketValue.toPlainString(),
                "CNY",
                "${allocation.weightPct.toPlainString()}%"
            )
        }
        return csv(rows)
    }

    fun csv(rows: List<List<String>>): String = rows.joinToString("\n") { row ->
        row.joinToString(",") { csvEscape(it) }
    } + "\n"

    fun csvEscape(value: String): String {
        val normalized = value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        return if (normalized.any { it == ',' || it == '"' }) {
            "\"${normalized.replace("\"", "\"\"")}\""
        } else {
            normalized
        }
    }

    private fun StringBuilder.tableRow(metric: String, value: String, note: String) {
        appendLine("| $metric | $value | ${escapePipe(note)} |")
    }

    private fun allocationRow(allocation: PortfolioAllocation): String =
        "| ${escapePipe(allocation.name)} | ${allocation.kind.name} | ${money(allocation.marketValue)} | " +
            "${pct(allocation.weightPct)} |"

    private fun escapePipe(value: String): String = value.replace("|", "\\|")

    /** Net external inflow implied by the period identity `P = V1 - V0 - F`. */
    private fun netExternalFlow(facts: PeriodFacts): BigDecimal? {
        val opening = facts.opening?.totalValue ?: return null
        val closing = facts.closing?.totalValue ?: return null
        val profit = facts.profit ?: return null
        return closing.subtract(opening).subtract(profit)
    }

    private fun xirrPct(result: XirrResult?): String = when (result) {
        is XirrResult.Available -> pct(BigDecimal.valueOf(result.annualizedRate * 100.0))
        is XirrResult.Unavailable, null -> MISSING
    }

    private fun xirrRaw(result: XirrResult?): String = when (result) {
        is XirrResult.Available -> BigDecimal.valueOf(result.annualizedRate * 100.0).toPlainString()
        else -> ""
    }

    private fun xirrMissingReason(result: XirrResult?): String? =
        (result as? XirrResult.Unavailable)?.let { xirrNote(it) }

    private fun xirrNote(result: XirrResult?): String = when (result) {
        is XirrResult.Available -> "逐笔外部现金流年化"
        is XirrResult.Unavailable -> when (result.reason) {
            XirrFailureReason.INVALID_CASHFLOWS -> "现金流不足或存在同日抵消，未计算"
            XirrFailureReason.MISSING_POSITIVE_FLOW -> "缺少正向现金流，未计算"
            XirrFailureReason.MISSING_NEGATIVE_FLOW -> "缺少负向现金流，未计算"
            XirrFailureReason.MULTIPLE_ROOTS -> "存在多个根，无法判定唯一年化收益"
            XirrFailureReason.NO_CONVERGENCE -> "迭代未收敛，未计算"
            XirrFailureReason.INCOMPLETE_VALUATION -> "资金流估值边界不完整，未计算"
        }
        null -> "现金流不足时不做外推"
    }

    private fun formatTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(marketZone).toLocalDateTime().toString().replace('T', ' ').take(16)

    private fun money(value: BigDecimal?): String {
        val number = value ?: return MISSING
        return "¥" + String.format(java.util.Locale.US, "%,.2f", number)
    }

    private fun magnitudePct(value: BigDecimal?): String {
        val number = value ?: return MISSING
        return "${plain(number)}%"
    }

    private fun pct(value: BigDecimal?): String {
        val number = value ?: return MISSING
        val sign = if (number.signum() > 0) "+" else ""
        return "$sign${plain(number)}%"
    }

    private fun plain(value: BigDecimal): String = value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
}
