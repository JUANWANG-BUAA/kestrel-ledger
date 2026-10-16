package cn.market.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import cn.market.assistant.domain.fund.FundNavEstimator
import cn.market.assistant.domain.fund.FundNavHistoryCache
import cn.market.assistant.domain.fund.FundMoneyYieldEstimate
import cn.market.assistant.domain.fund.FundWatchlistEntry
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            query.trim().length < 2 -> Text("输入至少 2 个字或基金代码，搜索全市场基金。", color = FundMuted, fontSize = 11.sp)
            isLoading -> Text("正在搜索基金…", color = FundMuted, fontSize = 11.sp)
            error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("基金搜索暂不可用：$error", Modifier.weight(1f), color = FundAccent, fontSize = 11.sp)
                TextButton(onClick = onRetry) { Text("重试", color = FundInk, fontSize = 10.sp) }
            }
            completedQuery == query.trim() && results.isEmpty() -> Text("没有匹配的公募基金，可检查代码或手动添加。", color = FundMuted, fontSize = 11.sp)
            results.isNotEmpty() -> Text("全市场基金 · ${results.size} 项 · ${results.first().source} ${results.first().fetchedAt.fundFetchTime()}", color = FundMuted, fontSize = 10.sp)
        }
        results.forEach { result ->
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                colors = CardDefaults.cardColors(containerColor = FundPaper),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2DED5))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(result.name, color = FundInk, fontSize = 12.sp)
                        Text("${result.code} · ${result.category}", color = FundMuted, fontSize = 10.sp)
                    }
                    TextButton(onClick = { onView(result) }) { Text("画像", color = FundInk, fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = { onUse(result) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = FundInk)
                    ) { Text("用于记账", fontSize = 10.sp) }
                }
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
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD8D8D2))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            when {
                isLoading -> Text("正在读取基金画像、净值和重仓…", color = FundMuted, fontSize = 11.sp)
                error != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("基金详情读取失败：$error", color = FundAccent, fontSize = 11.sp)
                    onRetry?.let { retry -> TextButton(onClick = retry) { Text("重试", color = FundInk, fontSize = 10.sp) } }
                }
                profile == null -> Text("等待基金详情", color = FundMuted, fontSize = 11.sp)
                else -> {
                    val moneyFund = profile.isMoneyMarketFund()
                    Text("${profile.name} · ${profile.code}", color = FundInk, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            profile.fundType,
                            profile.managementCompany,
                            profile.trackingIndexName?.let { "跟踪 $it" }
                        ).joinToString(" · ").ifBlank { "基金画像" },
                        color = FundMuted, fontSize = 10.sp
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
                                    when { it > 0 -> Color(0xFFC3444D); it < 0 -> Color(0xFF168568); else -> FundInk }
                                } ?: FundInk
                            )
                        }
                    }
                    Text(
                        "${if (moneyFund) "收益公布日" else "净值日期"} ${profile.navDate ?: "暂无"} · 申购 ${profile.subscriptionStatus ?: "未知"} · 赎回 ${profile.redemptionStatus ?: "未知"}",
                        color = FundMuted, fontSize = 10.sp, lineHeight = 15.sp
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
                    profile.riskLevel?.let { Text("风险等级 $it", color = FundMuted, fontSize = 10.sp) }
                    if (profile.navHistory.size > 1) {
                        FundNavTrend(profile)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(profile.navHistory.first().day, color = FundMuted, fontSize = 9.sp)
                            Text(
                                "近 ${profile.navHistory.size} 个${if (moneyFund) "收益记录" else "净值点"}",
                                color = FundMuted,
                                fontSize = 9.sp
                            )
                            Text(profile.navHistory.last().day, color = FundMuted, fontSize = 9.sp)
                        }
                    } else {
                        Text(profile.navError ?: "暂缺净值历史", color = FundMuted, fontSize = 10.sp)
                    }
                    historyCache?.let { cache ->
                        Text(
                            "本机净值库 · ${cache.totalCount} 个点 · " +
                                "${cache.firstDay ?: "暂无起点"} 至 ${cache.lastDay ?: "暂无"}" +
                                (cache.cachedAt?.let { " · 更新 ${it.fundFetchTime()}" } ?: ""),
                            color = FundMuted,
                            fontSize = 9.sp,
                            lineHeight = 14.sp
                        )
                    }
                    if (historySyncMessage != null) {
                        Text(historySyncMessage, color = FundMuted, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                    onBackfillFullHistory?.let { action ->
                        OutlinedButton(
                            onClick = action,
                            enabled = !isBackfillingHistory,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(11.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = FundInk)
                        ) { Text(if (isBackfillingHistory) "正在补齐历史…" else "补齐完整净值历史", fontSize = 10.sp) }
                    }
                    onToggleWatchlist?.let { action ->
                        OutlinedButton(
                            onClick = action,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(11.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = FundInk)
                        ) { Text(if (isWatchlisted) "移出基金自选" else "加入基金自选", fontSize = 10.sp) }
                    }
                    watchlistMessage?.let { Text(it, color = FundMuted, fontSize = 10.sp) }
                    if (profile.holdings.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("披露重仓 · ${profile.holdingsReportDate ?: "报告期未知"}", color = FundInk, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                                Text("占净值", color = FundMuted, fontSize = 9.sp)
                            }
                            profile.holdings.take(10).forEachIndexed { index, holding ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${index + 1}", color = FundMuted, fontSize = 9.sp)
                                    Text(holding.name, Modifier.weight(1f), color = FundInk, fontSize = 10.sp, maxLines = 1)
                                    Text(holding.code, color = FundMuted, fontSize = 9.sp)
                                    Text("${String.format(Locale.US, "%.2f", holding.weightPct)}%", color = FundInk, fontSize = 10.sp)
                                }
                            }
                        }
                    } else {
                        Text(profile.holdingsError ?: "当前没有可展示的披露重仓", color = FundMuted, fontSize = 10.sp)
                    }
                    Text("来源 ${profile.source} · 获取 ${profile.fetchedAt.fundFetchTime()}", color = FundMuted, fontSize = 9.sp)
                    onRetry?.let { retry ->
                        TextButton(onClick = retry) { Text("刷新基金数据", color = FundInk, fontSize = 10.sp) }
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
private fun FundMoneyYieldPanel(
    estimate: FundMoneyYieldEstimate?,
    isLoading: Boolean,
    onRefresh: (() -> Unit)?
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F1EA)),
        border = androidx.compose.foundation.BorderStroke(1.dp, FundGrid)
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("货币基金收益参考", color = FundInk, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(estimate?.grade?.label() ?: "公布收益", color = FundAccent, fontSize = 9.sp)
            }
            when {
                isLoading && estimate == null -> Text("正在读取万份收益历史…", color = FundMuted, fontSize = 10.sp)
                estimate == null -> Text("等待万份收益历史", color = FundMuted, fontSize = 10.sp)
                estimate.latestIncomePer10k != null -> Text(
                    "最近公布 ${estimate.latestIncomePer10k.moneyIncome()} 元/万份" +
                        (estimate.baseDate?.let { " · $it" } ?: ""),
                    color = FundInk, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
            }
            when {
                isLoading && estimate == null -> Unit
                estimate?.estimatedIncomePer10k != null -> {
                    Text(
                        "近 7 个公布日均值参考 ${estimate.estimatedIncomePer10k.moneyIncome()} 元/万份",
                        color = if (estimate.grade == FundEstimateGrade.LOW) FundMuted else FundInk,
                        fontSize = 12.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                    Text(
                        "${estimate.validationCount} 日样本外 MAE ${estimate.validationMaePer10k.moneyIncome()} · " +
                            "最近一期基线 MAE ${estimate.persistenceBaselineMaePer10k.moneyIncome()} 元/万份 · " +
                            "历史 ${estimate.historyCount} 条",
                        color = FundMuted, fontSize = 9.sp, lineHeight = 13.sp
                    )
                    estimate.reason?.let { Text(it, color = FundAccent, fontSize = 9.sp) }
                }
                estimate != null -> Text(
                    estimate.reason ?: "历史不足或验证效果不佳，暂不生成收益参考值",
                    color = FundMuted, fontSize = 10.sp, lineHeight = 14.sp
                )
            }
            Text(
                "万份收益是已公布的收益金额；均值仅作历史参考，不是单位净值或盘中净值估算，也不代表未来收益。" +
                    (estimate?.let { " 来源 ${it.source} · 获取 ${it.fetchedAt.fundFetchTime()}" } ?: ""),
                color = FundMuted, fontSize = 9.sp, lineHeight = 13.sp
            )
            onRefresh?.let { action ->
                TextButton(onClick = action, enabled = !isLoading) {
                    Text(if (isLoading) "刷新中" else "刷新万份收益历史", color = FundInk, fontSize = 10.sp)
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F1EA)),
        border = androidx.compose.foundation.BorderStroke(1.dp, FundGrid)
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("盘中净值估算", color = FundInk, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(estimate?.let { "${it.method.label()} · ${it.grade.label()}" } ?: "估算路由", color = FundAccent, fontSize = 9.sp)
            }
            when {
                isLoading && estimate == null -> Text(
                    if (usesDisclosedHoldings) "正在读取重仓行情与前复权历史并进行样本外验证…"
                    else "正在读取基准行情历史并进行样本外验证…",
                    color = FundMuted,
                    fontSize = 10.sp
                )
                estimate == null -> Text("等待行情与估值输入", color = FundMuted, fontSize = 10.sp)
                estimate.grade == FundEstimateGrade.NONE -> Text(
                    estimate.reason ?: "该品类不提供盘中估算数字",
                    color = FundMuted, fontSize = 10.sp, lineHeight = 14.sp
                )
                estimate.estimatedChangePct == null -> Text(
                    "— · ${estimate.reason ?: "当前数据不足，未生成估算"}",
                    color = FundMuted, fontSize = 10.sp, lineHeight = 14.sp
                )
                else -> {
                    val pct = requireNotNull(estimate.estimatedChangePct)
                    Text(
                        "估算涨跌 ${pct.asFundPercent()} · 单位净值 ${estimate.estimatedUnitNav.fundDecimal()}",
                        color = if (estimate.grade == FundEstimateGrade.LOW) FundMuted else FundInk,
                        fontSize = 12.sp,
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
                            color = FundMuted, fontSize = 9.sp
                        )
                    } else if (intervalCalibration != null && intervalCalibration.auditCount > 0) {
                        Text(
                            "近 ${intervalCalibration.auditCount} 日区间覆盖 " +
                                "${String.format(Locale.US, "%.0f", intervalCalibration.observedAuditCoveragePct ?: 0.0)}% ，未达到 80% 门槛，暂不显示区间",
                            color = FundMuted, fontSize = 9.sp
                        )
                    } else if (intervalCalibration != null) {
                        Text(
                            "误差区间校准样本 ${intervalCalibration.calibrationCount}/60，样本不足时不显示范围",
                            color = FundMuted, fontSize = 9.sp
                        )
                    } else {
                        Text("当前路由没有经过校准的误差区间", color = FundMuted, fontSize = 9.sp)
                    }
                    Text(
                        "${estimate.trackingIndexName ?: estimate.trackingIndexId ?: "跟踪指数"} · " +
                            "样本外 MAE ${estimate.outOfSampleMaePct.fundPercent()} · 零基线 R² ${estimate.rSquared.fundDecimal()} · " +
                            "覆盖 ${String.format(Locale.US, "%.0f", estimate.inputCoveragePct)}%",
                        color = FundMuted, fontSize = 9.sp, lineHeight = 13.sp
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
                        color = FundMuted, fontSize = 9.sp
                    )
                    estimate.reason?.let { Text(it, color = FundAccent, fontSize = 9.sp) }
                }
            }
            Text(
                "估算值不是基金公司公布的净值。" +
                    (estimate?.let { " 数据源 ${it.source ?: "—"} · 行情时点 ${it.sourceTime?.fundFetchTime() ?: "—"}" } ?: ""),
                color = FundMuted, fontSize = 9.sp, lineHeight = 13.sp
            )
            onRefresh?.let { action ->
                TextButton(onClick = action, enabled = !isLoading) {
                    Text(
                        if (isLoading) "估算中" else if (usesDisclosedHoldings) "刷新重仓行情后重估" else "更新基准行情后重估",
                        color = FundInk,
                        fontSize = 10.sp
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F1EA)),
        border = androidx.compose.foundation.BorderStroke(1.dp, FundGrid)
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("场内价格对照", color = FundInk, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(comparison?.marketSecid.orEmpty(), color = FundAccent, fontSize = 9.sp)
            }
            when {
                isRefreshing && comparison == null -> Text("正在读取场内交易价格…", color = FundMuted, fontSize = 10.sp)
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
                        color = FundMuted, fontSize = 9.sp, lineHeight = 13.sp
                    )
                    comparison?.reason?.let { Text(it, color = FundAccent, fontSize = 9.sp, lineHeight = 13.sp) }
                }
            }
            Text("市价与基金公司最近公布净值的差异，不代表盘中净值或申赎价格。", color = FundMuted, fontSize = 9.sp, lineHeight = 13.sp)
            onRefresh?.let { action ->
                TextButton(onClick = action, enabled = !isRefreshing) {
                    Text(if (isRefreshing) "刷新中" else "刷新场内价格", color = FundInk, fontSize = 10.sp)
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
            Text("基金自选", Modifier.weight(1f), color = FundInk, fontSize = 12.sp)
            Text("${entries.size} 个", color = FundMuted, fontSize = 10.sp)
        }
        message?.let { Text(it, color = FundMuted, fontSize = 10.sp) }
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
                        Text(entry.name, color = FundInk, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 1)
                        Text(
                            "${entry.code} · ${entry.category ?: "公募基金"} · ${entry.latest?.day ?: "暂无本地记录"} · " +
                                "${entry.navCount} 个${if (entry.isMoneyMarketFund()) "收益记录" else "净值点"}",
                            color = FundMuted,
                            fontSize = 9.sp
                        )
                        Text(
                            entry.cachedAt?.let { "天天基金 · 本机更新 ${it.fundFetchTime()}" } ?: "来源 天天基金 · 尚未同步净值",
                            color = FundMuted,
                            fontSize = 9.sp
                        )
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(entry.latest?.unitNav?.let { String.format(Locale.US, "%.4f", it) } ?: "—", color = FundInk, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text(
                            if (entry.isMoneyMarketFund()) "元/万份"
                            else entry.latest?.changePct?.let { "${if (it > 0) "+" else ""}${String.format(Locale.US, "%.2f", it)}%" } ?: "净值",
                            color = FundMuted,
                            fontSize = 9.sp
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onRefresh(entry) }, enabled = refreshingCode != entry.code) {
                        Text(if (refreshingCode == entry.code) "刷新中" else "刷新净值", color = FundInk, fontSize = 10.sp)
                    }
                    TextButton(onClick = { onRemove(entry) }) { Text("移除", color = FundMuted, fontSize = 10.sp) }
                }
            }
        }
    }
}

@Composable
private fun FundMetric(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = FundInk) {
    Column(modifier.background(Color(0xFFF4F1EA), RoundedCornerShape(10.dp)).padding(horizontal = 9.dp, vertical = 8.dp)) {
        Text(label, color = FundMuted, fontSize = 9.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = valueColor, fontSize = 12.sp)
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
    Canvas(
        Modifier.fillMaxWidth().height(76.dp).semantics {
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
