package cn.market.assistant

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.market.assistant.domain.market.ChartPeriod
import cn.market.assistant.domain.market.MarketOhlcBar
import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.domain.market.MarketRepository
import cn.market.assistant.domain.portfolio.Position
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import androidx.compose.runtime.ReadOnlyComposable

private val MarketDetailInk: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.ink
private val MarketDetailMuted: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.muted
private val MarketDetailAccent: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.accent
private val MarketDetailSoftAccent: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.accentSoft
private val MarketDetailGold: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.warn
private val MarketDetailWhite: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.surface
private val MarketDetailUp: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.up
private val MarketDetailDown: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.down
private val MarketDetailPanel = RoundedCornerShape(20.dp)

@Composable
internal fun MarketDetailScreen(
    secid: String, quote: MarketQuote?, marketRepository: MarketRepository, onBack: () -> Unit,
    onToggle: (Boolean) -> Unit, isWatched: Boolean, modifier: Modifier = Modifier
) {
    val instrument = MarketInstrumentCatalog.byId[secid]
    var period by remember(secid) { mutableStateOf(ChartPeriod.DAY) }
    var autoLive by remember(secid) { mutableStateOf(true) }
    var displayedQuote by remember(secid) { mutableStateOf(quote) }
    var style by remember(secid) { mutableStateOf(MarketChartStyle.CANDLE) }
    var showMa5 by remember(secid) { mutableStateOf(true) }
    var showMa20 by remember(secid) { mutableStateOf(false) }
    var showVolume by remember(secid) { mutableStateOf(true) }
    var showRsi by remember(secid) { mutableStateOf(false) }
    var showMacd by remember(secid) { mutableStateOf(false) }
    var bars by remember(secid, period) { mutableStateOf(emptyList<MarketOhlcBar>()) }
    var loading by remember(secid, period) { mutableStateOf(false) }
    var message by remember(secid, period) { mutableStateOf("") }
    var selectedIndex by remember(secid, period) { mutableStateOf(-1) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(quote) { displayedQuote = quote }
    fun load(force: Boolean = false) {
        if (loading) return
        val ttl = if (period.cacheKey == "m30") 15 * 60 * 1000L else 12 * 60 * 60 * 1000L
        val minimumBars = when (period) {
            ChartPeriod.INTRADAY -> 2
            ChartPeriod.FIVE_DAY -> 5
            ChartPeriod.DAY -> 10
            ChartPeriod.WEEK -> 4
            ChartPeriod.MONTH -> 3
        }
        scope.launch {
            loading = true
            val cached = runCatching { marketRepository.cachedChart(secid, period) }.getOrNull()
            cached?.let {
                bars = it.bars
                selectedIndex = filteredChartBars(it.bars, period).lastIndex
            }
            val cacheComplete = (cached?.bars?.size ?: 0) >= minimumBars
            val freshEnough = cacheComplete && (cached?.cachedAt ?: 0L) > 0 &&
                System.currentTimeMillis() - (cached?.cachedAt ?: 0L) < ttl
            if (!force && freshEnough) {
                loading = false
                return@launch
            }
            runCatching { marketRepository.refreshChart(secid, period) }
                .onSuccess { refreshed ->
                    if (refreshed.bars.isNotEmpty()) {
                        bars = refreshed.bars
                        selectedIndex = filteredChartBars(refreshed.bars, period).lastIndex
                    }
                    message = if (!refreshed.refreshed) "行情源没有返回数据，显示本机缓存" else "${period.label}已更新 · ${if (instrument?.yahooSymbol != null) "Yahoo Finance" else "公开行情源"}"
                }
                .onFailure { message = if (bars.isEmpty()) "当前市场暂不支持该周期：${it.message ?: "请稍后重试"}" else "更新失败，显示本机缓存" }
            loading = false
        }
    }
    LaunchedEffect(secid, period) { load() }
    LaunchedEffect(secid, period, autoLive) {
        if (period == ChartPeriod.INTRADAY && autoLive) {
            var lastChartRefresh = System.currentTimeMillis()
            while (isActive) {
                runCatching { marketRepository.refreshQuote(secid) }
                    .onSuccess { fresh -> displayedQuote = fresh }
                if (System.currentTimeMillis() - lastChartRefresh >= 60_000 && !loading) {
                    runCatching { marketRepository.refreshChart(secid, ChartPeriod.INTRADAY) }
                        .getOrNull()?.bars?.takeIf { it.isNotEmpty() }?.let { fresh ->
                            bars = fresh
                            selectedIndex = filteredChartBars(bars, period).lastIndex
                        }
                    lastChartRefresh = System.currentTimeMillis()
                }
                delay(15_000)
            }
        }
    }
    val visibleBars = filteredChartBars(bars, period)
    val selectedBar = visibleBars.getOrNull(selectedIndex.takeIf { it >= 0 } ?: visibleBars.lastIndex)
    val previousBar = selectedBar?.let { selected -> visibleBars.getOrNull(visibleBars.indexOf(selected) - 1) }
    val selectedPct = if (selectedBar != null && previousBar != null && previousBar.close != 0.0) (selectedBar.close / previousBar.close - 1.0) * 100 else null
    val lastDay = bars.lastOrNull()?.day?.take(8)
    val summaryBars = if (period == ChartPeriod.INTRADAY || period == ChartPeriod.FIVE_DAY) bars.filter { it.day.take(8) == lastDay } else listOfNotNull(bars.lastOrNull())
    val summaryOpen = summaryBars.firstOrNull()?.open
    val summaryHigh = summaryBars.maxOfOrNull { it.high }
    val summaryLow = summaryBars.minOfOrNull { it.low }
    val summaryClose = summaryBars.lastOrNull()?.close ?: displayedQuote?.price
    ScreenColumn(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MarketBackButton(label = "自选", onClick = onBack)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onToggle(!isWatched) }) { Text(if (isWatched) "★ 已自选" else "☆ 加自选", color = MarketDetailAccent) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(instrument?.name ?: "行情详情", color = MarketDetailInk, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            Surface(shape = CircleShape, color = MarketDetailSoftAccent) {
                Text(instrument?.market ?: "市场", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = MarketDetailAccent, fontSize = 10.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(displayedQuote?.let { if (it.source == "daily-close") "最近收盘 ${it.displayTime()}" else "行情更新 ${it.displayTime()}" } ?: "行情暂无报价", Modifier.weight(1f), color = MarketDetailMuted, fontSize = 11.sp)
            if (period == ChartPeriod.INTRADAY) {
                FilterChip(selected = autoLive, onClick = { autoLive = !autoLive },
                    label = { Text(if (autoLive) "实时 · 15秒刷新" else "实时 · 已暂停", fontSize = 10.sp) }, colors = assetChipColors())
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            androidx.compose.animation.AnimatedContent(targetState = displayedQuote?.price ?: bars.lastOrNull()?.close,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "price_change") { value ->
                Text(value?.let { String.format("%.2f", it) } ?: "—", color = MarketDetailInk, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            val pct = displayedQuote?.changePct ?: selectedPct
            if (pct != null) Text("${if (pct > 0) "+" else ""}${String.format("%.2f", pct)}%", color = marketDetailColor(BigDecimal.valueOf(pct)), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Card(Modifier.fillMaxWidth(), shape = CutCornerShape(4.dp), colors = CardDefaults.cardColors(containerColor = MarketDetailWhite),
            border = BorderStroke(1.dp, Color(0xFFD8D8D2))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("开盘" to summaryOpen, "最高" to summaryHigh, "最低" to summaryLow, "收盘" to summaryClose).forEach { (label, value) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(label, color = MarketDetailMuted, fontSize = 10.sp)
                        Text(value?.let { String.format("%.2f", it) } ?: "—", color = MarketDetailInk, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth(), shape = MarketDetailPanel, colors = CardDefaults.cardColors(containerColor = MarketDetailWhite),
            border = BorderStroke(1.dp, Color(0xFFD8D8D2)), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    ChartPeriod.entries.forEach { item ->
                        FilterChip(selected = period == item, onClick = { period = item },
                            label = { Text(item.label, fontSize = 11.sp) }, colors = assetChipColors())
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (period == ChartPeriod.INTRADAY || period == ChartPeriod.FIVE_DAY) "分时走势" else "${period.label}走势", color = MarketDetailInk, fontWeight = FontWeight.SemiBold)
                        Text(if (visibleBars.isEmpty()) "等待历史数据" else "${chartDate(visibleBars.first().day)} — ${chartDate(visibleBars.last().day)}  ·  ${if (period == ChartPeriod.INTRADAY || period == ChartPeriod.FIVE_DAY) "30分钟" else "${visibleBars.size} 根"}", color = MarketDetailMuted, fontSize = 10.sp)
                    }
                    TextButton(onClick = { load(force = true) }, enabled = !loading) { Text(if (loading) "加载中" else "更新") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    FilterChip(selected = style == MarketChartStyle.LINE, onClick = { style = MarketChartStyle.LINE }, label = { Text("走势") }, colors = assetChipColors())
                    FilterChip(selected = style == MarketChartStyle.CANDLE, onClick = { style = MarketChartStyle.CANDLE }, label = { Text("K线") }, colors = assetChipColors())
                    Spacer(Modifier.width(5.dp))
                    FilterChip(selected = showMa5, onClick = { showMa5 = !showMa5 }, label = { Text("MA5") }, colors = assetChipColors())
                    FilterChip(selected = showMa20, onClick = { showMa20 = !showMa20 }, label = { Text("MA20") }, colors = assetChipColors())
                    FilterChip(selected = showVolume, onClick = { showVolume = !showVolume }, label = { Text("成交量") }, colors = assetChipColors())
                    FilterChip(selected = showRsi, onClick = { showRsi = !showRsi }, label = { Text("RSI14") }, colors = assetChipColors())
                    FilterChip(selected = showMacd, onClick = { showMacd = !showMacd }, label = { Text("MACD") }, colors = assetChipColors())
                }
                androidx.compose.animation.AnimatedContent(
                    targetState = period to style,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "chart_transition"
                ) {
                    if (visibleBars.isNotEmpty()) MarketChart(visibleBars, style, showMa5, showMa20, showVolume, selectedIndex,
                        Modifier.fillMaxWidth().height(220.dp), onIndexSelected = { selectedIndex = it })
                    else Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                        Text(if (loading) "正在加载行情…" else "当前市场暂无此周期数据", color = MarketDetailMuted, fontSize = 13.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(visibleBars.firstOrNull()?.let { chartDate(it.day) } ?: "—", color = MarketDetailMuted, fontSize = 9.sp)
                    Text(selectedBar?.let { "已选 ${chartDate(it.day)}" } ?: "拖动图表查看", color = MarketDetailAccent, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                    Text(visibleBars.lastOrNull()?.let { chartDate(it.day) } ?: "—", color = MarketDetailMuted, fontSize = 9.sp)
                }
                AnimatedVisibility(
                    visible = showRsi,
                    enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(180))
                ) { IndicatorPane(visibleBars, selectedIndex, macd = false) }
                AnimatedVisibility(
                    visible = showMacd,
                    enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(180))
                ) { IndicatorPane(visibleBars, selectedIndex, macd = true) }
                AnimatedContent(targetState = selectedBar, transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(100)) }, label = "ohlc_selection") { selected ->
                    if (selected != null) {
                        val index = visibleBars.indexOf(selected).coerceAtLeast(0)
                        val ma5 = visibleBars.take(index + 1).takeLast(5).takeIf { it.size == 5 }?.map { it.close }?.average()
                        val ma20 = visibleBars.take(index + 1).takeLast(20).takeIf { it.size == 20 }?.map { it.close }?.average()
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${chartDate(selected.day)}  ·  涨跌 ${selectedPct?.let { "${if (it > 0) "+" else ""}${String.format("%.2f", it)}%" } ?: "—"}", color = MarketDetailInk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                listOf("开" to selected.open, "高" to selected.high, "低" to selected.low, "收" to selected.close).forEach { (label, value) ->
                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(label, color = MarketDetailMuted, fontSize = 9.sp)
                                        Text(String.format("%.2f", value), color = MarketDetailInk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("成交量 ${formatVolume(selected.volume)}", color = MarketDetailMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                if (showMa5) Text("MA5 ${ma5?.let { String.format("%.2f", it) } ?: "—"}", color = MarketDetailGold, fontSize = 10.sp)
                                if (showMa20) Text("MA20 ${ma20?.let { String.format("%.2f", it) } ?: "—"}", color = Color(0xFF8B78C8), fontSize = 10.sp)
                            }
                        }
                    } else Text("触摸图表可查看单根行情", color = MarketDetailMuted, fontSize = 11.sp)
                }
                Text("拖动图表查看单根数据；点选 MA5 / MA20 与成交量切换指标。", color = MarketDetailMuted, fontSize = 10.sp)
            }
        }
        if (message.isNotBlank()) Text(message, color = MarketDetailMuted, fontSize = 11.sp)
        Text("公开市场 OHLC 数据可能延迟；缺失周期不会用其他周期数据补画。不能替代交易终端报价。", color = MarketDetailMuted, fontSize = 10.sp, lineHeight = 15.sp)
    }
}


@Composable
private fun marketDetailColor(value: BigDecimal): Color = when (value.signum()) {
    1 -> MarketDetailUp
    -1 -> MarketDetailDown
    else -> MarketDetailMuted
}

private fun BigDecimal.marketDetailMoney(): String = setScale(2, RoundingMode.HALF_UP).toPlainString()

private fun BigDecimal.marketDetailQty(): String = stripTrailingZeros().toPlainString()
