package cn.market.assistant

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import cn.market.assistant.domain.portfolio.AccountCashEvent
import cn.market.assistant.domain.portfolio.AccountCashLedger
import cn.market.assistant.domain.portfolio.PeriodFacts
import cn.market.assistant.domain.portfolio.PortfolioAnalytics
import cn.market.assistant.domain.portfolio.PortfolioRiskAnalytics
import cn.market.assistant.domain.portfolio.PortfolioSnapshot
import cn.market.assistant.domain.portfolio.Position
import cn.market.assistant.domain.portfolio.PositionSnapshot
import cn.market.assistant.domain.portfolio.ReportPeriod
import cn.market.assistant.domain.portfolio.Trade
import cn.market.assistant.domain.portfolio.XirrResult
import cn.market.assistant.domain.portfolio.PortfolioRiskSummary
import cn.market.assistant.domain.market.ChartPeriod
import cn.market.assistant.domain.market.MarketRepository
import cn.market.assistant.domain.market.MarketVenue
import cn.market.assistant.domain.market.TradingCalendar
import cn.market.assistant.domain.market.TradingDaySet
import cn.market.assistant.domain.report.ReportExport
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

private val AnalysisPaper: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.paper

private fun BigDecimal.money() = setScale(2, RoundingMode.HALF_UP).toPlainString()

private enum class TrendRange(val label: String, val days: Long?) {
    WEEK("7 日", 7), MONTH("30 日", 30), QUARTER("90 日", 90), ALL("全部", null)
}

private enum class TrendMetric(val label: String) { ASSET("资产规模"), PROFIT("累计盈亏") }

private enum class AllocationBasis(val label: String) { MARKET("按市值"), COST("按成本") }

private data class TrendPoint(val day: LocalDate, val value: BigDecimal)

private data class AllocationSlice(val id: String, val name: String, val value: BigDecimal, val color: Color)

@Composable
internal fun AnalysisScreen(
    positions: List<Position>, trades: List<Trade>, cashEvents: List<AccountCashEvent>, cost: BigDecimal,
    holding: BigDecimal, realized: BigDecimal,
    snapshots: List<PortfolioSnapshot>, positionSnapshots: Map<LocalDate, List<PositionSnapshot>>,
    invalidatedSnapshotCount: Int, marketRepository: MarketRepository,
    riskFeatureState: PortfolioRiskFeatureState,
    onRecordSnapshot: () -> Unit, modifier: Modifier = Modifier
) {
    val pending = trades.count { !it.confirmed && !it.isReversed }
    val today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
    val context = LocalContext.current
    val sessionCalendar by produceState<TradingDaySet?>(initialValue = null, marketRepository) {
        value = withContext(Dispatchers.IO) {
            runCatching { marketRepository.cachedChart("1.000300", ChartPeriod.DAY).bars }
                .getOrNull()
                ?.let { bars -> TradingCalendar.fromBars(MarketVenue.SHANGHAI, bars, "1.000300") }
                ?.takeIf { calendar -> !calendar.inferred && calendar.days.size >= 20 }
        }
    }
    val riskParameters = riskFeatureState.parameters
    val benchmarkFeatureState = rememberPortfolioBenchmarkFeatureState(LocalContext.current, marketRepository)
    LaunchedEffect(benchmarkFeatureState.selectedId) { benchmarkFeatureState.load() }
    val currentValue = positions.fold(BigDecimal.ZERO) { sum, p -> sum + (p.value ?: BigDecimal.ZERO) }
    val cashBalance = AccountCashLedger.balance(cashEvents)
    val accountValue = if (cashBalance != null && positions.all { it.value != null }) currentValue + cashBalance.amount else null
    val accountRisk by produceState(
        initialValue = PortfolioRiskSummary(missingReason = "风险统计计算中…"),
        snapshots, positionSnapshots, trades, cashEvents, today, riskParameters
    ) {
        value = withContext(Dispatchers.Default) {
            PortfolioRiskAnalytics.trailingSummary(
                today, snapshots, positionSnapshots, trades, cashEvents,
                riskFreeAnnualRate = riskParameters.riskFreeAnnualRate,
                minimumAcceptableAnnualReturn = riskParameters.minimumAcceptableAnnualReturn,
                sessionCalendar = sessionCalendar
            )
        }
    }
    val pricedPositions = positions.filter { it.value != null }.sortedByDescending { it.value }
    var trendRange by remember { mutableStateOf(TrendRange.MONTH) }
    var trendMetric by remember { mutableStateOf(TrendMetric.ASSET) }
    var selectedTrendIndex by remember(trendRange, trendMetric) { mutableStateOf(-1) }
    var allocationBasis by remember { mutableStateOf(AllocationBasis.MARKET) }
    var selectedAllocationId by remember { mutableStateOf<String?>(null) }
    val trendDays = trendRange.days
    val rangeSnapshots = snapshots.filter { snapshot ->
        !snapshot.day.isAfter(today) && (trendDays == null || !snapshot.day.isBefore(today.minusDays(trendDays - 1)))
    }
    val useAccountTrend = trendMetric == TrendMetric.ASSET && cashBalance != null && rangeSnapshots.any { it.accountComplete }
    val trendPoints = rangeSnapshots.mapNotNull { snapshot ->
        val value = when (trendMetric) {
            TrendMetric.ASSET -> if (useAccountTrend) {
                snapshot.totalValue.takeIf { snapshot.accountComplete }
            } else snapshot.marketValue.takeIf { snapshot.complete }
            TrendMetric.PROFIT -> snapshot.totalProfit.takeIf { snapshot.complete }
        }
        value?.let { TrendPoint(snapshot.day, it) }
    }.sortedBy { it.day }
    val allocationValuationComplete = allocationBasis == AllocationBasis.COST || positions.all { it.value != null }
    val allocationSlices = buildList {
        if (allocationValuationComplete) positions.forEach { position ->
            val amount = if (allocationBasis == AllocationBasis.MARKET) position.value else position.cost
            amount?.takeIf { it.signum() > 0 }?.let {
                add(AllocationSlice(position.asset.symbol, position.asset.name, it, allocationColor(position.asset.symbol)))
            }
        }
        cashBalance?.amount?.takeIf { it.signum() > 0 }?.let {
            add(AllocationSlice("ACCOUNT_CASH", "账户现金", it, allocationColor("ACCOUNT_CASH")))
        }
    }.sortedByDescending { it.value }
    val allocationTotal = allocationSlices.fold(BigDecimal.ZERO) { sum, slice -> sum + slice.value }
    ScreenColumn(modifier) {
        BrandHeader("分析")
        Text("用真实流水和已录入价格计算。无连续历史净值时，不推测区间收益与回撤。", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
        if (invalidatedSnapshotCount > 0) InfoCard(
            "${invalidatedSnapshotCount} 个历史观察点因流水冲正失效",
            "原始快照仍保留在本机和备份中；受影响日期不参与盈亏与风险分析，后续观察会从最新流水重新开始。",
            Blue
        )
        val analysisPalette = MarketTheme.colors
        val analysisGeometry = MarketTheme.geometry
        Card(
            Modifier.fillMaxWidth().marketShadow(RoundedCornerShape(analysisGeometry.panel), MarketElevation.raised),
            shape = RoundedCornerShape(analysisGeometry.panel),
            colors = CardDefaults.cardColors(containerColor = analysisPalette.surface),
            border = BorderStroke(1.dp, analysisPalette.line.copy(alpha = 0.85f))
        ) {
            Column(
                Modifier.padding(analysisGeometry.xl),
                verticalArrangement = Arrangement.spacedBy(analysisGeometry.md)
            ) {
                Text("累计盈亏", color = analysisPalette.muted, style = MarketType.label)
                // With only unconfirmed trades the number is a meaningless zero, so name the reason
                // instead of showing ¥0.00 as the headline.
                if (holding.add(realized).signum() == 0 && pending > 0) {
                    Text("$pending 笔待确认", color = analysisPalette.ink, style = MarketType.amount)
                    Text("确认净值后才计入盈亏与风险统计", color = analysisPalette.muted, style = MarketType.caption)
                } else {
                    Text(
                        "${if (holding.add(realized).signum() > 0) "+" else ""}¥${holding.add(realized).money()}",
                        color = plColor(holding.add(realized)),
                        style = MarketType.amount
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(analysisPalette.line))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MarketMetric(
                        label = "账户总资产",
                        value = accountValue?.let { "¥${it.money()}" } ?: if (cashBalance == null) "未启用" else "待补价格",
                        valueColor = analysisPalette.ink,
                        modifier = Modifier.weight(1f)
                    )
                    MarketMetric(
                        label = "可用现金",
                        value = cashBalance?.let { "¥${it.amount.money()}" } ?: "—",
                        valueColor = analysisPalette.ink,
                        modifier = Modifier.weight(1f),
                        withDivider = true
                    )
                    MarketMetric(
                        label = "持仓市值",
                        value = "¥${currentValue.money()}",
                        valueColor = analysisPalette.ink,
                        modifier = Modifier.weight(1f),
                        withDivider = true
                    )
                }
                Text(
                    "持仓浮盈 ¥${holding.money()} · 已实现 ¥${realized.money()}",
                    color = analysisPalette.muted, style = MarketType.caption
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(analysisGeometry.md)) {
            MarketCompactStat("全部流水", "${trades.size} 笔", Modifier.weight(1f))
            MarketCompactStat("待确认", "$pending 笔", Modifier.weight(1f))
        }
        if (pending > 0) InfoCard("$pending 笔交易待确认", "待确认交易暂不计入份额和已确认周期统计。", Blue)
        MarketSectionHeader("资产走势", caption = "按已记录快照，拖动曲线查看单日")
        PortfolioTrendCard(
            points = trendPoints,
            metric = trendMetric,
            accountValueMode = useAccountTrend,
            selectedIndex = selectedTrendIndex,
            onMetricChange = { trendMetric = it },
            onRangeChange = { trendRange = it },
            range = trendRange,
            onPointSelected = { selectedTrendIndex = it }
        )
        Text("资产规模变化会受到入金和出金影响；累计盈亏来自已记录的持仓快照。", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
        PortfolioRiskCard(accountRisk, riskParameters, riskFeatureState::update)
        MarketSectionHeader("周期基准对比", caption = "通用参考指数，不含股息")
        PortfolioBenchmarkCard(benchmarkFeatureState)

        MarketSectionHeader(
            "资产配置",
            caption = if (cashBalance == null) "当前持仓 · 不含现金" else "账户持仓与现金"
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            AllocationBasis.entries.forEach { basis ->
                FilterChip(
                    selected = allocationBasis == basis,
                    onClick = { allocationBasis = basis; selectedAllocationId = null },
                    label = { Text(basis.label) }, colors = assetChipColors()
                )
            }
        }
        when {
            !allocationValuationComplete -> InfoCard("配置暂不可完整计算", "有持仓缺少当前报价；为避免展示错误权重，请先补充价格。", Gold)
            allocationSlices.isEmpty() -> EmptyCard("还没有可分析的配置", "录入持仓或启用现金跟踪后，这里会显示真实账户分布。")
            else -> AllocationCompositionCard(
                slices = allocationSlices,
                total = allocationTotal,
                selectedId = selectedAllocationId,
                basisLabel = allocationBasis.label,
                onSliceSelected = { id -> selectedAllocationId = if (selectedAllocationId == id) null else id }
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("周期复盘", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text("按每日观察快照计算；报价缺失时不补零", color = Muted, fontSize = 11.sp)
            }
            TextButton(onClick = onRecordSnapshot) { Text("记录观察点") }
        }
        Text("已记录 ${snapshots.size} 个有效观察日 · Modified Dietz 为资金流调整近似值；未启用现金跟踪时按持仓口径计算", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
        ReportPeriod.entries.forEach { period ->
            val facts = PortfolioAnalytics.facts(
                period, today, snapshots, positionSnapshots, trades, cashEvents, benchmarkFeatureState.series
            )
            PeriodReportCard(facts, snapshots, positionSnapshots) { format ->
                val provenance = reportProvenance(
                    context,
                    listOfNotNull("本机账本", benchmarkFeatureState.series?.source)
                )
                val text = when (format) {
                    ReportExportFormat.MARKDOWN -> ReportExport.periodFactsMarkdown(facts, provenance)
                    ReportExportFormat.CSV -> ReportExport.periodFactsCsv(facts, provenance)
                }
                shareLocalText(
                    context,
                    "养基助手-${facts.period.title}报告",
                    text,
                    if (format == ReportExportFormat.CSV) "text/csv" else "text/plain"
                )
            }
        }
        Text("报告只使用本机记录。启用现金跟踪且快照估值完整后才纳入账户现金；未记录的外部账户和分红不计入。记录频率不足时会显示缺口，不推测收益。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun PortfolioTrendCard(
    points: List<TrendPoint>,
    metric: TrendMetric,
    accountValueMode: Boolean,
    selectedIndex: Int,
    range: TrendRange,
    onMetricChange: (TrendMetric) -> Unit,
    onRangeChange: (TrendRange) -> Unit,
    onPointSelected: (Int) -> Unit
) {
    val activeIndex = selectedIndex.takeIf { it in points.indices } ?: points.lastIndex
    val active = points.getOrNull(activeIndex)
    val first = points.firstOrNull()
    val last = points.lastOrNull()
    val change = if (first != null && last != null) last.value - first.value else null
    val changePct = if (metric == TrendMetric.ASSET && first != null && last != null && first.value.signum() != 0) {
        change?.multiply(BigDecimal("100"))?.divide(first.value, 2, RoundingMode.HALF_UP)
    } else null
    val scopeLabel = when (metric) {
        TrendMetric.PROFIT -> "累计持仓盈亏"
        TrendMetric.ASSET -> if (accountValueMode) "账户总资产 · 含现金" else "持仓市值 · 不含现金"
    }

    val trendPalette = MarketTheme.colors
    val trendGeometry = MarketTheme.geometry
    Card(
        Modifier.fillMaxWidth()
            .marketShadow(RoundedCornerShape(trendGeometry.card), MarketElevation.card)
            .animateContentSize(tween(260)),
        shape = RoundedCornerShape(trendGeometry.card),
        colors = CardDefaults.cardColors(containerColor = trendPalette.surface),
        border = BorderStroke(1.dp, trendPalette.line)
    ) {
        Column(Modifier.padding(trendGeometry.lg), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(scopeLabel, color = Muted, fontSize = 11.sp)
                    Text(active?.day?.toString() ?: "等待有效观察点", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(active?.let { "¥${it.value.money()}" } ?: "—", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    val changeLabel = when {
                        change == null -> "区间变化 —"
                        metric == TrendMetric.ASSET && changePct != null -> "${if (changePct.signum() > 0) "+" else ""}${changePct.toPlainString()}% · ${if (change.signum() > 0) "+" else ""}¥${change.money()}"
                        else -> "区间变化 ${if (change.signum() > 0) "+" else ""}¥${change.money()}"
                    }
                    Text(changeLabel, color = change?.let { value -> plColor(value) } ?: Muted, fontSize = 11.sp)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TrendMetric.entries.forEach { item ->
                    MarketChoiceChip(label = item.label, selected = metric == item) { onMetricChange(item) }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TrendRange.entries.forEach { item ->
                    MarketChoiceChip(label = item.label, selected = range == item) { onRangeChange(item) }
                }
            }
            val chartSpan = if (points.size >= 2) (points.maxOf { it.value } - points.minOf { it.value }).toDouble() else 0.0
            if (points.size >= 2 && chartSpan > 0.005) {
                PortfolioTrendChart(points, activeIndex, onPointSelected, Modifier.fillMaxWidth().height(154.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(first?.day?.toString() ?: "", Modifier.weight(1f), color = Muted, fontSize = 11.sp)
                    Text("${points.size} 个有效观察点", color = Muted, fontSize = 11.sp)
                    Text(last?.day?.toString() ?: "", Modifier.weight(1f), color = Muted, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
                Text("拖动曲线查看单日记录；缺少价格的观察点不会补零。", color = Muted, fontSize = 11.sp)
            } else {
                Column(
                    Modifier.fillMaxWidth()
                        .background(trendPalette.surfaceAlt, RoundedCornerShape(trendGeometry.chip))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        when {
                            points.isEmpty() -> "暂无可绘制的观察数据"
                            points.size == 1 -> "已记录 1 个观察点"
                            else -> "两个观察点金额相同"
                        },
                        color = trendPalette.ink, style = MarketType.numberSmall, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when {
                            points.isEmpty() -> "记下交易并补充价格后，曲线会从有效快照开始。"
                            points.size == 1 -> "再积累一个有效观察日即可查看变化曲线。"
                            else -> "金额出现变化后，这里会画出走势；没有变化时不画一条假的平线。"
                        },
                        color = trendPalette.muted, style = MarketType.caption, lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PortfolioTrendChart(
    points: List<TrendPoint>,
    selectedIndex: Int,
    onPointSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val minValue = points.minOf { it.value.toDouble() }
    val maxValue = points.maxOf { it.value.toDouble() }
    val rawSpan = maxValue - minValue
    val span = rawSpan.coerceAtLeast(maxOf(kotlin.math.abs(maxValue), kotlin.math.abs(minValue)) * 0.015).coerceAtLeast(0.01)
    val low = minValue - span * 0.12
    val high = maxValue + span * 0.12
    val selected = selectedIndex.coerceIn(points.indices)
    val change = points.last().value - points.first().value
    val lineColor = plColor(change)
    val canvasSurface = White
    val gridColor = MarketTheme.colors.line.copy(alpha = 0.75f)
    val cursorColor = MarketTheme.colors.ink.copy(alpha = 0.35f)
    val reveal = remember(points) { Animatable(0f) }
    LaunchedEffect(points) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(460))
    }
    val accessibilitySummary = "${points.size} 个有效观察点，${points.first().day} 至 ${points.last().day}，最新 ¥${points.last().value.money()}"
    Canvas(
        modifier.fillMaxWidth().graphicsLayer { alpha = reveal.value }
            .semantics { contentDescription = "资产走势，$accessibilitySummary" }
            .pointerInput(points) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var totalDx = 0f
                    var totalDy = 0f
                    var selecting = false
                    fun selectAt(x: Float) {
                        val left = 8.dp.toPx()
                        val right = (size.width - 8.dp.toPx()).coerceAtLeast(left + 1f)
                        val fraction = ((x - left) / (right - left)).coerceIn(0f, 1f)
                        val firstDay = points.first().day.toEpochDay()
                        val lastDay = points.last().day.toEpochDay()
                        val targetDay = firstDay + ((lastDay - firstDay) * fraction).toLong()
                        val nearest = points.indices.minByOrNull { index ->
                            kotlin.math.abs(points[index].day.toEpochDay() - targetDay)
                        } ?: 0
                        onPointSelected(nearest)
                    }
                    do {
                        val event = awaitPointerEvent()
                        val changeEvent = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (changeEvent.pressed) {
                            val delta = changeEvent.position - changeEvent.previousPosition
                            totalDx += delta.x
                            totalDy += delta.y
                            if (!selecting && kotlin.math.abs(totalDx) > touchSlop &&
                                kotlin.math.abs(totalDx) > kotlin.math.abs(totalDy)
                            ) selecting = true
                            if (selecting) {
                                selectAt(changeEvent.position.x)
                                changeEvent.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (!selecting && kotlin.math.abs(totalDx) <= touchSlop && kotlin.math.abs(totalDy) <= touchSlop) {
                        selectAt(down.position.x)
                    }
                }
            }
    ) {
        val left = 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 9.dp.toPx()
        repeat(4) { row ->
            val y = top + (bottom - top) * row / 3f
            drawLine(gridColor, Offset(left, y), Offset(right, y), 1.dp.toPx())
        }
        val firstDay = points.first().day.toEpochDay().toDouble()
        val daySpan = (points.last().day.toEpochDay() - points.first().day.toEpochDay()).toDouble().coerceAtLeast(1.0)
        fun pointX(index: Int): Float = left + ((points[index].day.toEpochDay() - firstDay) / daySpan).toFloat() * (right - left)
        fun pointY(index: Int): Float = bottom - ((points[index].value.toDouble() - low) / (high - low)).toFloat() * (bottom - top)
        val progress = reveal.value
        val visibleLast = ((points.lastIndex * progress).toInt()).coerceIn(0, points.lastIndex)
        if (visibleLast >= 1) {
            val path = Path()
            for (index in 0..visibleLast) {
                if (index == 0) path.moveTo(pointX(index), pointY(index)) else path.lineTo(pointX(index), pointY(index))
            }
            val area = Path().apply {
                addPath(path)
                lineTo(pointX(visibleLast), bottom)
                lineTo(pointX(0), bottom)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.20f), Color.Transparent), top, bottom))
            drawPath(path, lineColor, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round))
        }
        val activeX = pointX(selected)
        val activeY = pointY(selected)
        drawLine(
            cursorColor, Offset(activeX, top), Offset(activeX, bottom), 1.dp.toPx(),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        )
        drawCircle(canvasSurface, radius = 6.dp.toPx(), center = Offset(activeX, activeY))
        drawCircle(lineColor, radius = 5.dp.toPx(), center = Offset(activeX, activeY), style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun AllocationCompositionCard(
    slices: List<AllocationSlice>,
    total: BigDecimal,
    selectedId: String?,
    basisLabel: String,
    onSliceSelected: (String) -> Unit
) {
    Card(
        Modifier.fillMaxWidth().animateContentSize(tween(240)),
        shape = TacticalPanel,
        colors = CardDefaults.cardColors(containerColor = White),
        border = BorderStroke(1.dp, Color(0xFFD8D8D2))
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("账户组成", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("$basisLabel · ${slices.size} 项", color = Muted, fontSize = 11.sp)
                }
                Text("¥${total.money()}", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AllocationDonut(slices, total, selectedId, Modifier.size(145.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    slices.forEach { slice ->
                        val percent = slice.value.multiply(BigDecimal("100")).divide(total, 1, RoundingMode.HALF_UP)
                        val selected = selectedId == slice.id
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (selected) AnalysisPaper else Color.Transparent)
                                .clickable { onSliceSelected(slice.id) }
                                .padding(horizontal = 5.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(Modifier.size(8.dp), shape = CircleShape, color = slice.color) {}
                            Spacer(Modifier.width(7.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(slice.name, color = Ink, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                                Text("¥${slice.value.money()}", color = Muted, fontSize = 11.sp, maxLines = 1)
                            }
                            Text("${percent.toPlainString()}%", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Text("点选图例可聚焦单项；市值口径要求全部持仓有有效价格。", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AllocationDonut(
    slices: List<AllocationSlice>,
    total: BigDecimal,
    selectedId: String?,
    modifier: Modifier = Modifier
) {
    val sweep = remember(slices) { Animatable(0f) }
    LaunchedEffect(slices) {
        sweep.snapTo(0f)
        sweep.animateTo(1f, tween(520))
    }
    val selected = slices.firstOrNull { it.id == selectedId }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().semantics {
            contentDescription = "账户配置圆环，${slices.joinToString { "${it.name} ${it.value.multiply(BigDecimal("100")).divide(total, 1, RoundingMode.HALF_UP)}%" }}"
        }) {
            val strokeWidth = 16.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val origin = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            var angle = -90f
            slices.forEach { slice ->
                val baseSweep = slice.value.multiply(BigDecimal("360"))
                    .divide(total, 8, RoundingMode.HALF_UP).toFloat()
                val drawnSweep = (baseSweep - 1.5f).coerceAtLeast(0.4f) * sweep.value
                val alpha = if (selectedId == null || selectedId == slice.id) 1f else 0.28f
                drawArc(
                    color = slice.color.copy(alpha = alpha),
                    startAngle = angle + 0.75f,
                    sweepAngle = drawnSweep,
                    useCenter = false,
                    topLeft = origin,
                    size = androidx.compose.ui.geometry.Size(diameter, diameter),
                    style = Stroke(width = if (selectedId == slice.id) 21.dp.toPx() else strokeWidth, cap = StrokeCap.Butt)
                )
                angle += baseSweep * sweep.value
            }
        }
        Column(Modifier.fillMaxWidth(0.72f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(selected?.name ?: "账户总额", color = Muted, fontSize = 11.sp, maxLines = 1)
            Text("¥${(selected?.value ?: total).money()}", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (selected != null) {
                val percent = selected.value.multiply(BigDecimal("100")).divide(total, 1, RoundingMode.HALF_UP)
                Text("${percent.toPlainString()}%", color = selected.color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun allocationColor(id: String): Color {
    val palette = listOf(
        Color(0xFF426F68), Color(0xFFB67B44), Color(0xFF7287A2), Color(0xFFAF5E5A),
        Color(0xFF8B78A8), Color(0xFF68929A), Color(0xFFC49B52), Color(0xFF64726D)
    )
    return palette[(id.hashCode() and Int.MAX_VALUE) % palette.size]
}

private enum class ReportExportFormat { MARKDOWN, CSV }

@Composable
private fun PeriodReportCard(
    facts: PeriodFacts,
    snapshots: List<PortfolioSnapshot>,
    positionSnapshots: Map<LocalDate, List<PositionSnapshot>>,
    onExport: (ReportExportFormat) -> Unit
) {
    var expanded by remember(facts.period) { mutableStateOf(false) }
    val accountBased = facts.opening?.accountComplete == true && facts.closing?.accountComplete == true
    val displayProfit = if (accountBased) facts.accountProfit else facts.profit
    val displayRate = if (accountBased) facts.accountModifiedDietzPct else facts.modifiedDietzPct
    val startDay = facts.opening?.day
    val endDay = facts.closing?.day
    val closing = facts.closing
    val series = snapshots.filter { snapshot ->
        startDay != null && endDay != null && !snapshot.day.isBefore(startDay) && !snapshot.day.isAfter(endDay) &&
            if (accountBased) snapshot.accountComplete else snapshot.totalProfit != null
    }
    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().animateContentSize(tween(240)),
        shape = CutCornerShape(topStart = 2.dp, topEnd = 17.dp, bottomEnd = 2.dp, bottomStart = 17.dp),
        colors = CardDefaults.cardColors(containerColor = White),
        border = BorderStroke(1.dp, Color(0xFFD8D8D2)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 5.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(facts.period.title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("${facts.start} — ${facts.end}", color = Muted, fontSize = 11.sp)
                }
                TextButton(
                    onClick = { onExport(ReportExportFormat.MARKDOWN) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) { Text("导出 MD", color = Blue, fontSize = 11.sp) }
                TextButton(
                    onClick = { onExport(ReportExportFormat.CSV) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) { Text("CSV", color = Blue, fontSize = 11.sp) }
                Text(if (expanded) "收起⌃" else "详情⌄", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(if (accountBased) "账户区间盈亏" else "持仓区间盈亏", color = Muted, fontSize = 11.sp)
                    Text(
                        displayProfit?.let { "${if (it.signum() > 0) "+" else ""}¥${it.money()}" } ?: "暂不可算",
                        color = displayProfit?.let { value -> plColor(value) } ?: Muted, fontSize = 21.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(if (accountBased) "账户 Modified Dietz" else "持仓 Modified Dietz", color = Muted, fontSize = 11.sp)
                    Text(displayRate?.let { "${if (it.signum() > 0) "+" else ""}${it.toPlainString()}%" } ?: "—",
                        color = displayRate?.let { value -> plColor(value) } ?: Muted, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (series.size >= 2) PortfolioProfitSparkline(series, Modifier.fillMaxWidth().height(46.dp))
            if (facts.missingReason != null) {
                Text(requireNotNull(facts.missingReason), color = Blue, fontSize = 11.sp, lineHeight = 16.sp)
            } else if (!accountBased) {
                Text("当前为持仓口径；${facts.accountMissingReason ?: "完整账户快照不可用"}", color = Blue, fontSize = 11.sp, lineHeight = 15.sp)
            } else if (facts.accountProfit == null) {
                Text(facts.accountMissingReason ?: "账户估值数据暂不可用", color = Blue, fontSize = 11.sp, lineHeight = 15.sp)
            } else {
                val opening = requireNotNull(facts.opening)
                val end = requireNotNull(facts.closing)
                val endRows = positionSnapshots[end.day].orEmpty()
                val sources = endRows.mapNotNull { it.quoteSource }.distinct().joinToString(" / ")
                val zone = java.time.ZoneId.of("Asia/Shanghai")
                val timeFormat = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                val fromTime = runCatching { java.time.Instant.parse(opening.observedAt).atZone(zone).format(timeFormat) }.getOrDefault("未知")
                val toTime = runCatching { java.time.Instant.parse(end.observedAt).atZone(zone).format(timeFormat) }.getOrDefault("未知")
                Text("观察 $fromTime → $toTime · 报价覆盖 ${end.pricedPositionCount}/${end.positionCount}${if (sources.isBlank()) "" else " · $sources"}",
                    color = Muted, fontSize = 11.sp)
            }
            if (facts.period != ReportPeriod.DAY && facts.benchmarkName != null) {
                val benchmarkText = facts.benchmarkReturnPct?.let { rate ->
                    val excess = facts.accountExcessReturnPct?.let { value ->
                        " · 账户 TWR 超额 ${if (value.signum() > 0) "+" else ""}${value.toPlainString()}%"
                    } ?: " · 账户 TWR 暂不可用"
                    "${facts.benchmarkName} 价格收益 ${if (rate.signum() > 0) "+" else ""}${rate.toPlainString()}%$excess"
                } ?: "${facts.benchmarkName} 暂不可比 · ${facts.benchmarkMissingReason ?: "基准日线不足"}"
                Text(benchmarkText, color = Blue, fontSize = 11.sp, lineHeight = 15.sp)
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(160)) + expandVertically(tween(220)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(180))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallStat("已确认流水", "${facts.transactionCount} 笔", Modifier.weight(1f))
                        SmallStat("买入 / 赎回", "¥${facts.buyAmount.money()} / ¥${facts.sellAmount.money()}", Modifier.weight(1f))
                    }
                    Text(
                        "已确认交易手续费 ¥${facts.transactionFeeAmount.money()} · 分红代扣税 ¥${facts.dividendWithholdingTax.money()}（不含未确认和已冲正流水）",
                        color = Muted, fontSize = 11.sp, lineHeight = 15.sp
                    )
                    if (facts.contributors.isNotEmpty()) {
                        Text("资产盈亏贡献 · 持仓利润快照期末减期初；占净盈亏比例可能超过 ±100%",
                            color = Muted, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
                        val endRowsByName = positionSnapshots[closing?.day]?.associateBy { it.asset.name }.orEmpty()
                        val visibleContributions = facts.contributors.take(3)
                        visibleContributions.forEach { contribution ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                val quote = endRowsByName[contribution.assetName]
                                Text(
                                    contribution.assetName + (quote?.quoteSource?.let { " · $it" } ?: ""),
                                    Modifier.weight(1f), color = Ink, fontSize = 11.sp, maxLines = 1
                                )
                                val share = contribution.shareOfNetProfitPct?.let { " · ${it.toPlainString()}%" }.orEmpty()
                                Text("${if (contribution.amount.signum() > 0) "+" else ""}¥${contribution.amount.money()}$share",
                                    color = plColor(contribution.amount), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        val remaining = facts.contributors.drop(visibleContributions.size)
                        if (remaining.isNotEmpty()) {
                            val remainingAmount = remaining.fold(BigDecimal.ZERO) { sum, item -> sum + item.amount }
                            val remainingShare = remaining.mapNotNull { it.shareOfNetProfitPct }
                                .takeIf { it.size == remaining.size }
                                ?.fold(BigDecimal.ZERO) { total, share -> total + share }
                                ?.let { " · ${it.toPlainString()}%" }.orEmpty()
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("其他资产（${remaining.size}）", Modifier.weight(1f), color = Ink, fontSize = 11.sp)
                                Text("${if (remainingAmount.signum() > 0) "+" else ""}¥${remainingAmount.money()}$remainingShare",
                                    color = plColor(remainingAmount), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        val contributionTotal = facts.contributors.fold(BigDecimal.ZERO) { sum, item -> sum + item.amount }
                        val reconciliation = facts.profit?.let { reported ->
                            if (contributionTotal.compareTo(reported) == 0) "贡献合计与持仓区间盈亏一致"
                            else "贡献合计与持仓区间盈亏相差 ¥${(contributionTotal - reported).money()}"
                        }
                        Text(reconciliation ?: "持仓区间盈亏暂不可核对", color = Muted, fontSize = 11.sp)
                    }
                    if (facts.period == ReportPeriod.MONTH && accountBased) {
                        val xirrText = when (val xirr = facts.accountXirr) {
                            is XirrResult.Available -> "${String.format("%.2f", xirr.annualizedRate * 100)}% 年化"
                            is XirrResult.Unavailable -> when (xirr.reason) {
                                cn.market.assistant.domain.portfolio.XirrFailureReason.MULTIPLE_ROOTS -> "存在多个可能解"
                                cn.market.assistant.domain.portfolio.XirrFailureReason.MISSING_NEGATIVE_FLOW -> "缺少期初投入"
                                cn.market.assistant.domain.portfolio.XirrFailureReason.MISSING_POSITIVE_FLOW -> "缺少期末正资产或回款"
                                cn.market.assistant.domain.portfolio.XirrFailureReason.INCOMPLETE_VALUATION -> "期初或期末估值报价过期 / 不完整"
                                else -> "现金流不足以求解"
                            }
                            null -> "—"
                        }
                        Text("账户 XIRR（按实际天数年化）：$xirrText", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                    if (accountBased) {
                        val twrText = facts.accountTwrPct?.let { "${if (it.signum() > 0) "+" else ""}${it.toPlainString()}%" }
                            ?: "暂不可用 · ${facts.accountTwrMissingReason ?: "缺少完整估值边界"}"
                        Text("账户 TWR（链式区间收益）：$twrText", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                    if (facts.period != ReportPeriod.DAY && facts.benchmarkName != null) {
                        val benchmarkDetails = buildString {
                            append("参考指数：${facts.benchmarkName}")
                            facts.benchmarkSource?.let { append(" · 来源 $it") }
                            if (facts.benchmarkStartDay != null || facts.benchmarkEndDay != null) {
                                append(" · 日线 ${facts.benchmarkStartDay ?: "?"} → ${facts.benchmarkEndDay ?: "?"}")
                            }
                            facts.benchmarkMissingReason?.let { append(" · $it") }
                            append("。指数为价格收益，未含股息；超额收益以账户 TWR 减基准价格收益计算。")
                        }
                        Text(benchmarkDetails, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                    if (accountBased && facts.period != ReportPeriod.DAY) {
                        val drawdownText = facts.accountMaxDrawdownPct?.let { value ->
                            val dates = if (facts.accountMaxDrawdownPeakDay != null && facts.accountMaxDrawdownTroughDay != null) {
                                " · ${facts.accountMaxDrawdownPeakDay} → ${facts.accountMaxDrawdownTroughDay}"
                            } else ""
                            "${value.toPlainString()}%$dates"
                        } ?: buildString {
                            append("暂不可用 · ${facts.accountMaxDrawdownMissingReason ?: "缺少连续新鲜快照"}")
                            facts.accountMaxDrawdownMissingDays.takeIf { it.isNotEmpty() }?.let { missingDays ->
                                append("（${missingDays.take(5).joinToString()}${if (missingDays.size > 5) " 等" else ""}）")
                            }
                        }
                        Text("账户最大回撤（TWR路径）：$drawdownText", color = Ink, fontSize = 11.sp, lineHeight = 16.sp,
                            fontWeight = FontWeight.Medium)
                    }
                    if (accountBased) {
                        if (facts.accountAllocationMissingReason != null) {
                            Text("账户配置暂不可用 · ${facts.accountAllocationMissingReason}", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                        } else {
                            if (facts.period != ReportPeriod.DAY) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SmallStat("账户 HHI", facts.accountAllocationHhi?.toPlainString() ?: "—", Modifier.weight(1f))
                                    SmallStat("有效资产数", facts.accountEffectiveAssetCount?.toPlainString() ?: "—", Modifier.weight(1f))
                                }
                            }
                            if (facts.period == ReportPeriod.MONTH) {
                                Text("账户配置 · 含现金", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                facts.accountAllocations.take(5).forEach { allocation ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(allocation.name, Modifier.weight(1f), color = Ink, fontSize = 11.sp, maxLines = 1)
                                        Text("${allocation.weightPct.toPlainString()}% · ¥${allocation.marketValue.money()}",
                                            color = Muted, fontSize = 11.sp)
                                    }
                                }
                            }
                            Text(if (facts.period == ReportPeriod.DAY) "相较昨日配置变化 · 百分点" else "期初至期末权重变化 · 百分点",
                                color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            if (facts.accountAllocationChanges.isNotEmpty()) {
                                facts.accountAllocationChanges.take(3).forEach { change ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(change.name, Modifier.weight(1f), color = Ink, fontSize = 11.sp, maxLines = 1)
                                        Text("${if (change.changePercentagePoints.signum() > 0) "+" else ""}${change.changePercentagePoints.toPlainString()} pp",
                                            color = plColor(change.changePercentagePoints), fontSize = 11.sp)
                                    }
                                }
                            } else Text("权重没有变化", color = Muted, fontSize = 11.sp)
                        }
                    }
                    Text(if (accountBased) "账户盈亏已扣除外部净入金；Modified Dietz 按日期加权近似。TWR 与回撤使用逐日流量调整收益；回撤要求每日快照连续、报价新鲜且流水估值完整。月度 XIRR 按实际天数年化。" else "当前仅能核算持仓口径。启用现金跟踪并积累完整账户快照后，才计算账户盈亏、收益率和 XIRR。", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }
    }
}

/** Cumulative investor profit for each observation day; shared by the chart and the empty gate. */
internal fun sparklineValues(snapshots: List<PortfolioSnapshot>): List<Double> {
    val accountBased = snapshots.all { it.accountComplete }
    return if (accountBased && snapshots.isNotEmpty()) {
        val firstValue = requireNotNull(snapshots.first().totalValue)
        var cumulativeExternalFlow = BigDecimal.ZERO
        snapshots.mapIndexedNotNull { index, snapshot ->
            if (index > 0) cumulativeExternalFlow += snapshot.externalNetFlow ?: return@mapIndexedNotNull null
            (requireNotNull(snapshot.totalValue) - firstValue - cumulativeExternalFlow).toDouble()
        }
    } else snapshots.mapNotNull { it.totalProfit?.toDouble() }
}

/** True when the recorded snapshots actually move, so a flat month is not drawn as a fake trend. */
internal fun hasSparklineVariation(snapshots: List<PortfolioSnapshot>): Boolean {
    val values = sparklineValues(snapshots)
    return values.size >= 2 && (values.max() - values.min()) > 0.005
}

@Composable
internal fun PortfolioProfitSparkline(snapshots: List<PortfolioSnapshot>, modifier: Modifier = Modifier) {
    val values = sparklineValues(snapshots)
    if (values.size < 2) return
    val palette = MarketTheme.colors
    val min = minOf(0.0, values.minOrNull() ?: 0.0)
    val max = maxOf(0.0, values.maxOrNull() ?: 0.0)
    val range = (max - min).coerceAtLeast(0.01)
    val moving = (max - min) > 0.005
    val color = when {
        !moving -> palette.muted
        (values.lastOrNull() ?: 0.0) >= (values.firstOrNull() ?: 0.0) -> palette.up
        else -> palette.down
    }
    Canvas(modifier) {
        val zeroY = size.height - ((0.0 - min) / range).toFloat() * size.height
        drawLine(palette.line, Offset(0f, zeroY), Offset(size.width, zeroY), 1.dp.toPx())
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.lastIndex).coerceAtLeast(1)
            val y = size.height - ((value - min) / range).toFloat() * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (moving) {
            val area = Path().apply {
                addPath(path)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(
                area,
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.01f))
                ),
                style = androidx.compose.ui.graphics.drawscope.Fill
            )
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        val lastY = size.height - ((values.last() - min) / range).toFloat() * size.height
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(size.width, lastY))
    }
}
