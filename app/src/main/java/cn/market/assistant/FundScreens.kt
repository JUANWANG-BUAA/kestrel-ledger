package cn.market.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.market.assistant.domain.fund.FundProfile
import cn.market.assistant.domain.fund.FundSearchResult
import cn.market.assistant.domain.fund.FundListedPriceAnalytics
import cn.market.assistant.domain.fund.FundMarketPriceComparison
import cn.market.assistant.domain.fund.FundEstimateGrade
import cn.market.assistant.domain.fund.FundEstimateMethod
import cn.market.assistant.domain.fund.FundNavEstimate
import cn.market.assistant.domain.fund.FundPerformanceAnalytics
import cn.market.assistant.domain.fund.FundNavEstimator
import cn.market.assistant.domain.fund.FundNavHistoryCache
import cn.market.assistant.domain.fund.FundMoneyYieldEstimate
import cn.market.assistant.domain.fund.FundWatchlistEntry
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.runtime.ReadOnlyComposable

private val FundInk: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.ink
private val FundMuted: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.muted
private val FundAccent: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.accent
private val FundGrid: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.line
private val FundPaper: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.surface

private const val FUND_SEARCH_PAGE_SIZE = 40

@Composable
internal fun FundSearchResultsPanel(
    query: String,
    results: List<FundSearchResult>,
    isLoading: Boolean,
    completedQuery: String,
    error: String?,
    onView: (FundSearchResult) -> Unit,
    onRetry: () -> Unit,
    onUse: (FundSearchResult) -> Unit
) {
    var visibleResultCount by remember(query) { mutableIntStateOf(FUND_SEARCH_PAGE_SIZE) }
    val visibleResults = results.take(visibleResultCount)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            query.trim().length < 2 -> Text("输入基金名称、代码或拼音简称，搜索全市场基金。", color = FundMuted, style = MarketType.label)
            isLoading -> Text("正在搜索基金…", color = FundMuted, style = MarketType.label)
            error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("基金搜索暂不可用：$error", Modifier.weight(1f), color = FundAccent, style = MarketType.label)
                TextButton(onClick = onRetry) { Text("重试", color = FundInk, style = MarketType.label) }
            }
            completedQuery == query.trim() && results.isEmpty() -> Text("没有匹配的公募基金，可检查代码或手动添加。", color = FundMuted, style = MarketType.label)
            results.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "全市场基金 · 返回 ${results.size} 项 · ${results.first().source} ${results.first().fetchedAt.fundFetchTime()}",
                    color = FundMuted,
                    style = MarketType.label
                )
                if (results.size > visibleResults.size) {
                    Text("已显示 ${visibleResults.size} / ${results.size} 项", color = FundMuted, style = MarketType.caption)
                } else if (results.size >= 500) {
                    Text("当前最多返回 500 项；继续输入名称、代码或拼音可缩小范围。", color = FundMuted, style = MarketType.caption)
                }
            }
        }
        visibleResults.forEach { result ->
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                colors = CardDefaults.cardColors(containerColor = FundPaper),
                border = androidx.compose.foundation.BorderStroke(1.dp, MarketTheme.colors.line.copy(alpha = 0.85f))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(result.name, color = FundInk, style = MarketType.numberSmall)
                        Text("${result.code} · ${result.category}", color = FundMuted, style = MarketType.label)
                    }
                    TextButton(onClick = { onView(result) }) { Text("画像", color = FundInk, style = MarketType.label) }
                    OutlinedButton(
                        onClick = { onUse(result) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = FundInk)
                    ) { Text("用于记账", style = MarketType.label) }
                }
            }
        }
        if (results.size > visibleResults.size && !isLoading && completedQuery == query.trim()) {
            TextButton(
                onClick = {
                    visibleResultCount = (visibleResultCount + FUND_SEARCH_PAGE_SIZE).coerceAtMost(results.size)
                }
            ) {
                Text("加载更多结果 · 已显示 ${visibleResults.size}/${results.size}", color = FundInk, style = MarketType.label)
            }
        }
    }
}

@Composable
internal fun FundProfileCard(
    profile: FundProfile?,
    isLoading: Boolean,
    error: String?,
    onUse: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    historyCache: FundNavHistoryCache? = null,
    isBackfillingHistory: Boolean = false,
    historySyncMessage: String? = null,
    onBackfillFullHistory: (() -> Unit)? = null,
    isWatchlisted: Boolean = false,
    watchlistMessage: String? = null,
    onToggleWatchlist: (() -> Unit)? = null,
    navEstimate: FundNavEstimate? = null,
    isEstimatingNav: Boolean = false,
    onRefreshEstimate: (() -> Unit)? = null,
    moneyYieldEstimate: FundMoneyYieldEstimate? = null,
    listedPriceComparison: FundMarketPriceComparison? = null,
    isRefreshingListedPrice: Boolean = false,
    onRefreshListedPrice: (() -> Unit)? = null
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = FundPaper),
        border = androidx.compose.foundation.BorderStroke(1.dp, MarketTheme.colors.line.copy(alpha = 0.85f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            when {
                isLoading -> Text("正在读取基金画像、净值和重仓…", color = FundMuted, style = MarketType.label)
                error != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("基金详情读取失败：$error", color = FundAccent, style = MarketType.label)
                    onRetry?.let { retry -> TextButton(onClick = retry) { Text("重试", color = FundInk, style = MarketType.label) } }
                }
                profile == null -> Text("等待基金详情", color = FundMuted, style = MarketType.label)
                else -> {
                    val moneyFund = profile.isMoneyMarketFund()
                    Text("${profile.name} · ${profile.code}", color = FundInk, style = MarketType.number, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            profile.fundType,
                            profile.managementCompany,
                            profile.trackingIndexName?.let { "跟踪 $it" }
                        ).joinToString(" · ").ifBlank { "基金画像" },
                        color = FundMuted, style = MarketType.label
                    )
                    if (moneyFund) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FundMetric("万份收益 · 元/万份", profile.unitNav.fundDecimal(), Modifier.weight(1f))
                            FundMetric("累计万份收益", profile.accumulatedNav.fundDecimal(), Modifier.weight(1f))
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FundMetric("单位净值", profile.unitNav.fundDecimal(), Modifier.weight(1f))
                            FundMetric("累计净值", profile.accumulatedNav.fundDecimal(), Modifier.weight(1f))
                            FundMetric(
                                "日涨跌",
                                profile.changePct.fundPercent(),
                                Modifier.weight(1f),
                                profile.changePct?.takeIf { it.isFinite() }?.let {
                                    when { it > 0 -> MarketTheme.colors.up; it < 0 -> MarketTheme.colors.down; else -> FundInk }
                                } ?: FundInk
                            )
                        }
                    }
                    Text(
                        "${if (moneyFund) "收益公布日" else "净值日期"} ${profile.navDate ?: "暂无"} · 申购 ${profile.subscriptionStatus ?: "未知"} · 赎回 ${profile.redemptionStatus ?: "未知"}",
                        color = FundMuted, style = MarketType.label, lineHeight = 15.sp
                    )
                    if (moneyFund) {
                        FundMoneyYieldPanel(moneyYieldEstimate, isLoading = isLoading, onRefresh = onRetry)
                    } else {
                        FundNavEstimatePanel(profile, navEstimate, isEstimatingNav, onRefreshEstimate)
                    }
                    if (FundListedPriceAnalytics.marketSecid(profile.code) != null) {
                        FundListedMarketPricePanel(
                            comparison = listedPriceComparison,
                            isRefreshing = isRefreshingListedPrice,
                            onRefresh = onRefreshListedPrice
                        )
                    }
                    if (!moneyFund) {
                        FundPerformanceSection(profile, historyCache)
                    }
                    if (profile.navHistory.size > 1) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (moneyFund) "万份收益走势" else "单位净值走势",
                                color = FundInk, style = MarketType.section,
                                modifier = Modifier.weight(1f)
                            )
                            profile.riskLevel?.let {
                                Text("风险等级 $it", color = FundMuted, style = MarketType.caption)
                            }
                        }
                        FundNavTrend(profile)
                    } else {
                        Text(profile.navError ?: "暂缺净值历史", color = FundMuted, style = MarketType.label)
                    }
                    historyCache?.let { cache ->
                        Text(
                            "本机净值库 · ${cache.totalCount} 个点 · " +
                                "${cache.firstDay ?: "暂无起点"} 至 ${cache.lastDay ?: "暂无"}" +
                                (cache.cachedAt?.let { " · 更新 ${it.fundFetchTime()}" } ?: ""),
                            color = FundMuted,
                            style = MarketType.caption,
                            lineHeight = 14.sp
                        )
                    }
                    if (historySyncMessage != null) {
                        Text(historySyncMessage, color = FundMuted, style = MarketType.label, lineHeight = 14.sp)
                    }
                    onBackfillFullHistory?.let { action ->
                        OutlinedButton(
                            onClick = action,
                            enabled = !isBackfillingHistory,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(11.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = FundInk)
                        ) { Text(if (isBackfillingHistory) "正在补齐历史…" else "补齐完整净值历史", style = MarketType.label) }
                    }
                    onToggleWatchlist?.let { action ->
                        OutlinedButton(
                            onClick = action,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(11.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = FundInk)
                        ) { Text(if (isWatchlisted) "移出基金自选" else "加入基金自选", style = MarketType.label) }
                    }
                    watchlistMessage?.let { Text(it, color = FundMuted, style = MarketType.label) }
                    if (profile.holdings.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("披露重仓 · ${profile.holdingsReportDate ?: "报告期未知"}", color = FundInk, style = MarketType.label, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                                Text("占净值", color = FundMuted, style = MarketType.caption)
                            }
                            profile.holdings.take(10).forEachIndexed { index, holding ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${index + 1}", color = FundMuted, style = MarketType.caption)
                                    Text(holding.name, Modifier.weight(1f), color = FundInk, style = MarketType.label, maxLines = 1)
                                    Text(holding.code, color = FundMuted, style = MarketType.caption)
                                    Text("${String.format(Locale.US, "%.2f", holding.weightPct)}%", color = FundInk, style = MarketType.label)
                                }
                            }
                        }
                    } else {
                        Text(profile.holdingsError ?: "当前没有可展示的披露重仓", color = FundMuted, style = MarketType.label)
                    }
                    Text("来源 ${profile.source} · 获取 ${profile.fetchedAt.fundFetchTime()}", color = FundMuted, style = MarketType.caption)
                    onRetry?.let { retry ->
                        TextButton(onClick = retry) { Text("刷新基金数据", color = FundInk, style = MarketType.label) }
                    }
                    onUse?.let { action ->
                        OutlinedButton(
                            onClick = action,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(11.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = FundInk)
                        ) { Text("用于记账") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FundProfileSummary(
    profile: FundProfile?,
    isLoading: Boolean,
    error: String?,
    navEstimate: FundNavEstimate?,
    onOpenDetails: () -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    fallbackName: String? = null,
    fallbackCode: String? = null,
    onChange: (() -> Unit)? = null,
    onUse: (() -> Unit)? = null
) {
    val current = profile
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = FundPaper),
        border = androidx.compose.foundation.BorderStroke(1.dp, MarketTheme.colors.line.copy(alpha = 0.85f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        current?.let { "${it.name} · ${it.code}" }
                            ?: listOfNotNull(fallbackName, fallbackCode).joinToString(" · ").ifBlank { "基金详情" },
                        color = FundInk,
                        style = MarketType.number,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                    Text(
                        current?.let {
                            listOfNotNull(it.fundType, it.managementCompany).joinToString(" · ").ifBlank { "基金画像" }
                        } ?: if (isLoading) "正在读取基金画像" else "净值、估算、走势和披露重仓",
                        color = FundMuted,
                        style = MarketType.label
                    )
                }
                onChange?.let {
                    TextButton(onClick = it) { Text("更换", color = FundAccent, style = MarketType.label) }
                }
                TextButton(onClick = onClose) {
                    MarketIcon("close", size = 14.dp, tint = FundMuted)
                    Text("关闭", color = FundMuted, style = MarketType.label)
                }
            }
            when {
                isLoading && current == null -> Text("正在读取基金详情…", color = FundMuted, style = MarketType.label)
                error != null && current == null -> {
                    Text("基金详情读取失败：$error", color = FundAccent, style = MarketType.label)
                    TextButton(onClick = onRetry) { Text("重试", color = FundInk, style = MarketType.label) }
                }
                current == null -> Text("等待基金详情", color = FundMuted, style = MarketType.label)
                else -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SummaryMetric("单位净值", current.unitNav.fundDecimal(), Modifier.weight(1f))
                        SummaryMetric(
                            "日涨跌", current.changePct.fundPercent(), Modifier.weight(1f),
                            current.changePct?.takeIf { it.isFinite() }?.let {
                                when {
                                    it > 0 -> MarketTheme.colors.up
                                    it < 0 -> MarketTheme.colors.down
                                    else -> FundInk
                                }
                            } ?: FundInk
                        )
                        SummaryMetric("净值日期", current.navDate ?: "暂无", Modifier.weight(1f))
                    }
                    navEstimate?.estimatedUnitNav?.let { estimate ->
                        Text(
                            "盘中估算 ${estimate.fundDecimal()} · ${navEstimate.estimatedChangePct.fundPercent()} · ${navEstimate.grade.label()}",
                            color = FundMuted,
                            style = MarketType.label
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onOpenDetails,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(11.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = FundInk)
                        ) { Text("查看详情", style = MarketType.label) }
                        onUse?.let { action ->
                            OutlinedButton(
                                onClick = action,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(11.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = FundInk)
                            ) { Text("用于记账", style = MarketType.label) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, modifier: Modifier = Modifier, color: Color = FundInk) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = FundMuted, style = MarketType.caption)
        Text(value, color = color, style = MarketType.numberSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    }
}

/** Full-height detail layer with an explicit close affordance for the inline fund summary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FundDetailSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MarketTheme.colors.paper
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("基金详情", color = FundInk, style = MarketType.title)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    MarketIcon("close", size = 14.dp, tint = FundMuted)
                    Text("关闭", color = FundMuted, style = MarketType.label)
                }
            }
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}


@Composable
internal fun FundPerformanceSection(profile: FundProfile, historyCache: FundNavHistoryCache?) {
    val points = historyCache?.points?.takeIf { it.size > profile.navHistory.size } ?: profile.navHistory
    val report = remember(profile.code, points) { FundPerformanceAnalytics.analyze(points) }
    if (report == null) return
    val palette = MarketTheme.colors
    val oneYear = report.periodReturns.firstOrNull { it.label == "1Y" }
    val headline = oneYear?.takeIf { it.returnPct != null }
        ?: report.periodReturns.firstOrNull { it.label == "6M" }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("业绩与风险", color = FundInk, style = MarketType.section, modifier = Modifier.weight(1f))
            Text("${report.basis} · ${report.pointCount} 点", color = FundMuted, style = MarketType.caption)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PerformanceMetric(
                if (headline?.label == "6M") "近6月" else "近1年",
                headline?.returnPct,
                signed = true,
                modifier = Modifier.weight(1f)
            )
            PerformanceMetric(
                if (oneYear?.annualizedPct == null) "年化 · 不足1年" else "年化",
                oneYear?.annualizedPct,
                signed = true,
                modifier = Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PerformanceMetric(
                "最大回撤",
                report.drawdown.maxDrawdownPct?.let { -it },
                signed = false,
                modifier = Modifier.weight(1f),
                color = palette.down
            )
            PerformanceMetric(
                "年化波动", report.risk.annualizedVolatilityPct, signed = false,
                modifier = Modifier.weight(1f), color = FundInk
            )
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(palette.surfaceAlt)) {
            PerformanceTableHeader()
            report.periodReturns.forEach { period ->
                PerformanceTableRow(period.label.performanceLabel(), period.returnPct, period.annualizedPct)
                Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line.copy(alpha = 0.7f)))
            }
        }
        Text(
            "夏普 ${report.risk.sharpe.performanceRatio()} · 索提诺 ${report.risk.sortino.performanceRatio()} · " +
                "卡玛 ${report.risk.calmar.performanceRatio()} · 正收益日 ${report.risk.positiveDayRatioPct.performancePct()}",
            color = FundMuted, style = MarketType.caption, lineHeight = 16.sp
        )
        Text(
            buildString {
                append("最大回撤 ")
                append(report.drawdown.maxDrawdownPct?.let { "-${String.format(Locale.US, "%.2f", it)}%" } ?: "—")
                report.drawdown.peakDay?.let { append(" · $it") }
                report.drawdown.troughDay?.let { append(" → $it") }
                append(" · 修复 ")
                append(report.drawdown.recoveryDay ?: "仍在修复")
                append(" · 当前回撤 ")
                append(report.drawdown.currentDrawdownPct?.let { "-${String.format(Locale.US, "%.2f", it)}%" } ?: "—")
            },
            color = FundMuted, style = MarketType.caption, lineHeight = 16.sp
        )
        report.risk.missingReason?.let { Text(it, color = FundMuted, style = MarketType.caption) }
        report.dataNote?.let { Text(it, color = FundMuted, style = MarketType.caption, lineHeight = 16.sp) }
        val missing = report.periodReturns.filter { it.returnPct == null }.map { it.label.performanceLabel() }
        if (missing.isNotEmpty()) {
            Text("样本不足：${missing.distinct().joinToString(" / ")}；补齐完整净值历史后可算。", color = FundMuted, style = MarketType.caption)
        }
    }
}

@Composable
private fun PerformanceMetric(
    label: String,
    value: Double?,
    signed: Boolean,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color? = null
) {
    val palette = MarketTheme.colors
    val resolved = color ?: value?.let { plColor(BigDecimal.valueOf(it)) } ?: palette.muted
    Surface(
        modifier,
        color = palette.surface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.line.copy(alpha = 0.8f))
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = palette.muted, style = MarketType.caption)
            Text(
                value.performancePct(signed),
                color = resolved,
                style = MarketType.numberSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PerformanceTableHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("区间", Modifier.weight(1f), color = FundMuted, style = MarketType.caption, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Text("收益", Modifier.weight(1.2f), color = FundMuted, style = MarketType.caption, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        Text("年化", Modifier.weight(1.2f), color = FundMuted, style = MarketType.caption, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun PerformanceTableRow(label: String, returnPct: Double?, annualizedPct: Double?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = FundInk, style = MarketType.label)
        Text(returnPct.performancePct(signed = true), Modifier.weight(1.2f), color = returnPct?.let { plColor(BigDecimal.valueOf(it)) } ?: FundMuted, style = MarketType.caption, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        Text(annualizedPct.performancePct(signed = true), Modifier.weight(1.2f), color = annualizedPct?.let { plColor(BigDecimal.valueOf(it)) } ?: FundMuted, style = MarketType.caption, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

private fun String.performanceLabel(): String = when (this) {
    "1M" -> "1月"
    "3M" -> "3月"
    "6M" -> "6月"
    "1Y" -> "1年"
    "3Y" -> "3年"
    "5Y" -> "5年"
    else -> this
}

private fun Double?.performancePct(signed: Boolean = false): String {
    if (this == null || !isFinite()) return "—"
    val prefix = if (signed && this > 0) "+" else ""
    return prefix + String.format(Locale.US, "%.2f", this) + "%"
}

private fun Double?.performanceRatio(): String =
    if (this == null || !isFinite()) "—" else String.format(Locale.US, "%.2f", this)

@Composable
private fun FundMoneyYieldPanel(
    estimate: FundMoneyYieldEstimate?,
    isLoading: Boolean,
    onRefresh: (() -> Unit)?
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(containerColor = MarketTheme.colors.surfaceAlt),
        border = androidx.compose.foundation.BorderStroke(1.dp, FundGrid)
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("货币基金收益参考", color = FundInk, style = MarketType.label, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(estimate?.grade?.label() ?: "公布收益", color = FundAccent, style = MarketType.caption)
            }
            when {
                isLoading && estimate == null -> Text("正在读取万份收益历史…", color = FundMuted, style = MarketType.label)
                estimate == null -> Text("等待万份收益历史", color = FundMuted, style = MarketType.label)
                estimate.latestIncomePer10k != null -> Text(
                    "最近公布 ${estimate.latestIncomePer10k.moneyIncome()} 元/万份" +
                        (estimate.baseDate?.let { " · $it" } ?: ""),
                    color = FundInk, style = MarketType.label, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
            }
            when {
                isLoading && estimate == null -> Unit
                estimate?.estimatedIncomePer10k != null -> {
                    Text(
                        "近 7 个公布日均值参考 ${estimate.estimatedIncomePer10k.moneyIncome()} 元/万份",
                        color = if (estimate.grade == FundEstimateGrade.LOW) FundMuted else FundInk,
                        style = MarketType.numberSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                    Text(
                        "${estimate.validationCount} 日样本外 MAE ${estimate.validationMaePer10k.moneyIncome()} · " +
                            "最近一期基线 MAE ${estimate.persistenceBaselineMaePer10k.moneyIncome()} 元/万份 · " +
                            "历史 ${estimate.historyCount} 条",
                        color = FundMuted, style = MarketType.caption, lineHeight = 13.sp
                    )
                    estimate.reason?.let { Text(it, color = FundAccent, style = MarketType.caption) }
                }
                estimate != null -> Text(
                    estimate.reason ?: "历史不足或验证效果不佳，暂不生成收益参考值",
                    color = FundMuted, style = MarketType.label, lineHeight = 14.sp
                )
            }
            Text(
                "万份收益是已公布的收益金额；均值仅作历史参考，不是单位净值或盘中净值估算，也不代表未来收益。" +
                    (estimate?.let { " 来源 ${it.source} · 获取 ${it.fetchedAt.fundFetchTime()}" } ?: ""),
                color = FundMuted, style = MarketType.caption, lineHeight = 13.sp
            )
            onRefresh?.let { action ->
                TextButton(onClick = action, enabled = !isLoading) {
                    Text(if (isLoading) "刷新中" else "刷新万份收益历史", color = FundInk, style = MarketType.label)
                }
            }
        }
    }
}

@Composable
private fun FundNavEstimatePanel(
    profile: FundProfile,
    estimate: FundNavEstimate?,
    isLoading: Boolean,
    onRefresh: (() -> Unit)?
) {
    val usesDisclosedHoldings = FundNavEstimator.usesDisclosedHoldingsRoute(profile)
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(containerColor = MarketTheme.colors.surfaceAlt),
        border = androidx.compose.foundation.BorderStroke(1.dp, FundGrid)
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("盘中净值估算", color = FundInk, style = MarketType.label, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(estimate?.let { "${it.method.label()} · ${it.grade.label()}" } ?: "估算路由", color = FundAccent, style = MarketType.caption)
            }
            when {
                isLoading && estimate == null -> Text(
                    if (usesDisclosedHoldings) "正在读取重仓行情与前复权历史并进行样本外验证…"
                    else "正在读取基准行情历史并进行样本外验证…",
                    color = FundMuted,
                    style = MarketType.label
                )
                estimate == null -> Text("等待行情与估值输入", color = FundMuted, style = MarketType.label)
                estimate.grade == FundEstimateGrade.NONE -> Text(
                    estimate.reason ?: "该品类不提供盘中估算数字",
                    color = FundMuted, style = MarketType.label, lineHeight = 14.sp
                )
                estimate.estimatedChangePct == null -> Text(
                    "— · ${estimate.reason ?: "当前数据不足，未生成估算"}",
                    color = FundMuted, style = MarketType.label, lineHeight = 14.sp
                )
                else -> {
                    val pct = requireNotNull(estimate.estimatedChangePct)
                    Text(
                        "估算涨跌 ${pct.asFundPercent()} · 单位净值 ${estimate.estimatedUnitNav.fundDecimal()}",
                        color = if (estimate.grade == FundEstimateGrade.LOW) FundMuted else FundInk,
                        style = MarketType.numberSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                    val intervalCalibration = estimate.intervalCalibration
                    if (intervalCalibration?.isUsable == true && estimate.lowerBoundPct != null && estimate.upperBoundPct != null) {
                        Text(
                            "目标 ${intervalCalibration.targetCoveragePct.toInt()}% 区间 " +
                                "${estimate.lowerBoundPct.asFundPercent()} 至 ${estimate.upperBoundPct.asFundPercent()} · " +
                                "${intervalCalibration.calibrationCount} 日校准 · " +
                                "最近 ${intervalCalibration.auditCount} 日覆盖 " +
                                "${String.format(Locale.US, "%.0f", intervalCalibration.observedAuditCoveragePct ?: 0.0)}%",
                            color = FundMuted, style = MarketType.caption
                        )
                    } else if (intervalCalibration != null && intervalCalibration.auditCount > 0) {
                        Text(
                            "近 ${intervalCalibration.auditCount} 日区间覆盖 " +
                                "${String.format(Locale.US, "%.0f", intervalCalibration.observedAuditCoveragePct ?: 0.0)}% ，未达到 80% 门槛，暂不显示区间",
                            color = FundMuted, style = MarketType.caption
                        )
                    } else if (intervalCalibration != null) {
                        Text(
                            "误差区间校准样本 ${intervalCalibration.calibrationCount}/60，样本不足时不显示范围",
                            color = FundMuted, style = MarketType.caption
                        )
                    } else {
                        Text("当前路由没有经过校准的误差区间", color = FundMuted, style = MarketType.caption)
                    }
                    Text(
                        "${estimate.trackingIndexName ?: estimate.trackingIndexId ?: "跟踪指数"} · " +
                            "样本外 MAE ${estimate.outOfSampleMaePct.fundPercent()} · 零基线 R² ${estimate.rSquared.fundDecimal()} · " +
                            "覆盖 ${String.format(Locale.US, "%.0f", estimate.inputCoveragePct)}%",
                        color = FundMuted, style = MarketType.caption, lineHeight = 13.sp
                    )
                    Text(
                        when (estimate.method) {
                            FundEstimateMethod.INDEX_OLS ->
                                "基于 ${estimate.trainingCount} 日训练 / ${estimate.validationCount} 日验证 · " +
                                    "${estimate.baseNavDate ?: "净值日期未知"} 官方净值作基准"
                            FundEstimateMethod.LINKED_ETF_OLS ->
                                "以 ${estimate.trackingIndexName ?: "联接目标 ETF"} 场内价格回归 · " +
                                    "${estimate.trainingCount} 日训练 / ${estimate.validationCount} 日验证"
                            FundEstimateMethod.BOND_INDEX_OLS ->
                                "以 ${estimate.trackingIndexName ?: "债券指数"} 做 60 日回归 · " +
                                    "${estimate.trainingCount} 日训练 / ${estimate.validationCount} 日验证"
                            FundEstimateMethod.CONVERTIBLE_INDEX_OLS ->
                                "以 ${estimate.trackingIndexName ?: "中证转债"} 做 60 日回归 · " +
                                    "${estimate.trainingCount} 日训练 / ${estimate.validationCount} 日验证"
                            FundEstimateMethod.QDII_INDEX_OLS ->
                                "以 ${estimate.trackingIndexName ?: "海外基准"} 的 T-1 海外收盘对齐 · " +
                                    "${estimate.trainingCount} 日训练 / ${estimate.validationCount} 日验证"
                            FundEstimateMethod.DISCLOSED_HOLDINGS_OLS ->
                                "按 ${estimate.inputHoldingsReportDate ?: "最近一期"} 重仓报告构建收益因子 · " +
                                    "${estimate.trainingCount} 日训练 / ${estimate.validationCount} 日验证"
                            else -> "以 ${estimate.baseNavDate ?: "最近披露"} 官方净值作基准 · 当前没有样本外精度验证"
                        },
                        color = FundMuted, style = MarketType.caption
                    )
                    estimate.reason?.let { Text(it, color = FundAccent, style = MarketType.caption) }
                }
            }
            Text(
                "估算值不是基金公司公布的净值。" +
                    (estimate?.let { " 数据源 ${it.source ?: "—"} · 行情时点 ${it.sourceTime?.fundFetchTime() ?: "—"}" } ?: ""),
                color = FundMuted, style = MarketType.caption, lineHeight = 13.sp
            )
            onRefresh?.let { action ->
                TextButton(onClick = action, enabled = !isLoading) {
                    Text(
                        if (isLoading) "估算中" else if (usesDisclosedHoldings) "刷新重仓行情后重估" else "更新基准行情后重估",
                        color = FundInk,
                        style = MarketType.label
                    )
                }
            }
        }
    }
}

@Composable
private fun FundListedMarketPricePanel(
    comparison: FundMarketPriceComparison?,
    isRefreshing: Boolean,
    onRefresh: (() -> Unit)?
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(containerColor = MarketTheme.colors.surfaceAlt),
        border = androidx.compose.foundation.BorderStroke(1.dp, FundGrid)
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("场内价格对照", color = FundInk, style = MarketType.label, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(comparison?.marketSecid.orEmpty(), color = FundAccent, style = MarketType.caption)
            }
            when {
                isRefreshing && comparison == null -> Text("正在读取场内交易价格…", color = FundMuted, style = MarketType.label)
                else -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FundMetric(
                            "场内市价",
                            comparison?.marketPrice?.let { "¥${String.format(Locale.US, "%.3f", it)}" } ?: "—",
                            Modifier.weight(1f)
                        )
                        FundMetric(
                            "最近公布净值",
                            comparison?.latestNav?.let { "¥${String.format(Locale.US, "%.4f", it)}" } ?: "—",
                            Modifier.weight(1f)
                        )
                        FundMetric(
                            "相对差异",
                            comparison?.premiumDiscountPct?.asFundPercent() ?: "—",
                            Modifier.weight(1f),
                            FundAccent
                        )
                    }
                    Text(
                        "净值日期 ${comparison?.navDate ?: "—"} · " +
                            "来源 ${comparison?.source ?: "—"} · 报价 ${comparison?.quoteTime?.fundFetchTime() ?: "—"}",
                        color = FundMuted, style = MarketType.caption, lineHeight = 13.sp
                    )
                    comparison?.reason?.let { Text(it, color = FundAccent, style = MarketType.caption, lineHeight = 13.sp) }
                }
            }
            Text("市价与基金公司最近公布净值的差异，不代表盘中净值或申赎价格。", color = FundMuted, style = MarketType.caption, lineHeight = 13.sp)
            onRefresh?.let { action ->
                TextButton(onClick = action, enabled = !isRefreshing) {
                    Text(if (isRefreshing) "刷新中" else "刷新场内价格", color = FundInk, style = MarketType.label)
                }
            }
        }
    }
}

private fun FundEstimateGrade.label(): String = when (this) {
    FundEstimateGrade.HIGH -> "高质量路由"
    FundEstimateGrade.MEDIUM -> "中等质量"
    FundEstimateGrade.LOW -> "参考意义有限"
    FundEstimateGrade.NONE -> "不提供数字"
    FundEstimateGrade.UNAVAILABLE -> "暂无估算"
}

private fun FundEstimateMethod.label(): String = when (this) {
    FundEstimateMethod.INDEX_OLS -> "指数回归"
    FundEstimateMethod.LINKED_ETF_OLS -> "联接 ETF 回归"
    FundEstimateMethod.BOND_INDEX_OLS -> "债券指数回归"
    FundEstimateMethod.CONVERTIBLE_INDEX_OLS -> "转债指数回归"
    FundEstimateMethod.QDII_INDEX_OLS -> "QDII 滞后对齐回归"
    FundEstimateMethod.DISCLOSED_HOLDINGS_OLS -> "重仓股池回归"
    FundEstimateMethod.DISCLOSED_HOLDINGS -> "披露重仓"
    FundEstimateMethod.NONE -> "不建模"
    FundEstimateMethod.UNAVAILABLE -> "无可用路由"
}

@Composable
internal fun FundWatchlistPanel(
    entries: List<FundWatchlistEntry>,
    refreshingCode: String?,
    message: String?,
    onRefresh: (FundWatchlistEntry) -> Unit,
    onRemove: (FundWatchlistEntry) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("基金自选", Modifier.weight(1f), color = FundInk, style = MarketType.numberSmall)
            Text("${entries.size} 个", color = FundMuted, style = MarketType.label)
        }
        message?.let { Text(it, color = FundMuted, style = MarketType.label) }
        entries.forEach { entry ->
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = FundPaper),
                border = androidx.compose.foundation.BorderStroke(1.dp, FundGrid)
            ) {
                Row(
                    Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.name, color = FundInk, style = MarketType.numberSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 1)
                        Text(
                            "${entry.code} · ${entry.category ?: "公募基金"} · ${entry.latest?.day ?: "暂无本地记录"} · " +
                                "${entry.navCount} 个${if (entry.isMoneyMarketFund()) "收益记录" else "净值点"}",
                            color = FundMuted,
                            style = MarketType.caption
                        )
                        Text(
                            entry.cachedAt?.let { "天天基金 · 本机更新 ${it.fundFetchTime()}" } ?: "来源 天天基金 · 尚未同步净值",
                            color = FundMuted,
                            style = MarketType.caption
                        )
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(entry.latest?.unitNav?.let { String.format(Locale.US, "%.4f", it) } ?: "—", color = FundInk, style = MarketType.numberSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text(
                            if (entry.isMoneyMarketFund()) "元/万份"
                            else entry.latest?.changePct?.let { "${if (it > 0) "+" else ""}${String.format(Locale.US, "%.2f", it)}%" } ?: "净值",
                            color = FundMuted,
                            style = MarketType.caption
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onRefresh(entry) }, enabled = refreshingCode != entry.code) {
                        Text(if (refreshingCode == entry.code) "刷新中" else "刷新净值", color = FundInk, style = MarketType.label)
                    }
                    TextButton(onClick = { onRemove(entry) }) { Text("移除", color = FundMuted, style = MarketType.label) }
                }
            }
        }
    }
}

@Composable
private fun FundMetric(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = FundInk) {
    Column(
        modifier
            .background(MarketTheme.colors.surfaceAlt, RoundedCornerShape(MarketTheme.geometry.chip))
            .padding(horizontal = MarketTheme.geometry.md, vertical = MarketTheme.geometry.sm),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(label, color = FundMuted, style = MarketType.caption, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(value, color = valueColor, style = MarketType.number, maxLines = 1)
    }
}

@Composable
private fun FundNavTrend(profile: FundProfile) {
    val points = profile.navHistory.takeLast(90)
    val moneyFund = profile.isMoneyMarketFund()
    val values = points.map { it.unitNav }
    val minimum = values.minOrNull() ?: 0.0
    val maximum = values.maxOrNull() ?: 0.0
    val range = (maximum - minimum).coerceAtLeast(0.000001)
    val gridColor = FundGrid
    val accentColor = FundAccent
    Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            Modifier.width(48.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(maximum.fundDecimal(), color = FundMuted, style = MarketType.caption)
            Text(((maximum + minimum) / 2.0).fundDecimal(), color = FundMuted, style = MarketType.caption)
            Text(minimum.fundDecimal(), color = FundMuted, style = MarketType.caption)
        }
        Canvas(
            Modifier.weight(1f).fillMaxHeight().semantics {
                contentDescription = "${profile.name} ${if (moneyFund) "万份收益" else "单位净值"}趋势，${points.first().day} 至 ${points.last().day}"
            }
        ) {
            val left = 2.dp.toPx()
            val right = size.width - 2.dp.toPx()
            val top = 5.dp.toPx()
            val bottom = size.height - 5.dp.toPx()
            repeat(3) { index ->
                val y = top + (bottom - top) * index / 2f
                drawLine(gridColor, Offset(left, y), Offset(right, y), 1.dp.toPx())
            }
            val line = Path()
            points.forEachIndexed { index, point ->
                val x = left + (right - left) * index / (points.lastIndex).coerceAtLeast(1)
                val y = bottom - ((values[index] - minimum) / range).toFloat() * (bottom - top)
                if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            drawPath(line, accentColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
            val lastX = left + (right - left)
            val lastY = bottom - ((values.last() - minimum) / range).toFloat() * (bottom - top)
            drawCircle(accentColor, radius = 3.dp.toPx(), center = Offset(lastX, lastY))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(points.first().day, color = FundMuted, style = MarketType.caption)
        Text("${points.size} 个点", color = FundMuted, style = MarketType.caption)
        Text(points.last().day, color = FundMuted, style = MarketType.caption)
    }
}

private fun FundProfile.isMoneyMarketFund(): Boolean =
    fundType.orEmpty().contains("货币") || name.contains("货币")

private fun FundWatchlistEntry.isMoneyMarketFund(): Boolean =
    category.orEmpty().contains("货币") || name.contains("货币")

private fun Double?.moneyIncome(): String =
    this?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.4f", it) } ?: "—"

private fun Double?.fundDecimal(): String = this?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.4f", it) } ?: "—"

private fun Double?.fundPercent(): String = this?.takeIf { it.isFinite() }?.let {
    "${if (it > 0) "+" else ""}${String.format(Locale.US, "%.2f", it)}%"
} ?: "—"

private fun Double?.asFundPercent(): String = fundPercent()

private fun Long.fundFetchTime(): String = runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(ZoneId.of("Asia/Shanghai"))
        .format(Instant.ofEpochSecond(this))
}.getOrDefault("时间未知")
