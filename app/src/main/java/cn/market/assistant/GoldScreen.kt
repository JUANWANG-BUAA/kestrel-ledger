package cn.market.assistant

import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.backup.EncryptedBackup
import cn.market.assistant.domain.market.MarketBoardSnapshot
import cn.market.assistant.domain.market.MarketBoardType
import cn.market.assistant.domain.market.MarketRepository
import cn.market.assistant.domain.market.ChartPeriod
import cn.market.assistant.domain.market.MarketOhlcBar
import cn.market.assistant.data.market.MarketRepositoryImpl
import cn.market.assistant.domain.fund.FundRepository
import cn.market.assistant.data.fund.FundRepositoryImpl
import cn.market.assistant.snapshot.DailyPortfolioSnapshotScheduler
import cn.market.assistant.domain.portfolio.AssetKind
import cn.market.assistant.domain.portfolio.AccountCashEvent
import cn.market.assistant.domain.portfolio.AccountCashLedger
import cn.market.assistant.domain.portfolio.PeriodFacts
import cn.market.assistant.domain.portfolio.PortfolioAnalytics
import cn.market.assistant.domain.portfolio.PortfolioSnapshot
import cn.market.assistant.domain.portfolio.Position
import cn.market.assistant.domain.portfolio.ReportPeriod
import cn.market.assistant.data.assistant.ReadOnlyAgentTools
import cn.market.assistant.alert.AlertEvaluationScheduler
import cn.market.assistant.domain.portfolio.Trade

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@Composable
internal fun GoldDashboardScreen(
    quote: MarketQuote?, domesticQuote: MarketQuote?, internationalCnyGramQuote: MarketQuote?, etfQuote: MarketQuote?, marketRepository: MarketRepository,
    ledger: Ledger,
    goldPositions: List<Position>, message: String, quoteLoading: Boolean, onRefresh: () -> Unit,
    onOpenEtf: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier
) {
    var period by remember { mutableStateOf(ChartPeriod.INTRADAY) }
    var style by remember { mutableStateOf(MarketChartStyle.CANDLE) }
    var showMa5 by remember { mutableStateOf(true) }
    var showMa20 by remember { mutableStateOf(false) }
    var showVolume by remember { mutableStateOf(true) }
    var showRsi by remember { mutableStateOf(false) }
    var showMacd by remember { mutableStateOf(false) }
    var refreshRevision by remember { mutableStateOf(0) }
    var bars by remember(period) { mutableStateOf(emptyList<MarketOhlcBar>()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var selectedIndex by remember(period) { mutableStateOf(-1) }
    val scope = rememberCoroutineScope()
    fun loadChart(force: Boolean = false) {
        if (loading) return
        val ttl = if (period == ChartPeriod.INTRADAY || period == ChartPeriod.FIVE_DAY) 15 * 60 * 1000L else 12 * 60 * 60 * 1000L
        scope.launch {
            loading = true
            val cached = runCatching { marketRepository.cachedChart("1.518880", period) }.getOrNull()
            cached?.let {
                bars = it.bars
                selectedIndex = filteredChartBars(it.bars, period).lastIndex
            }
            if (!force && cached != null && cached.bars.size >= 2 && cached.cachedAt > 0 &&
                System.currentTimeMillis() - cached.cachedAt < ttl) {
                loading = false
                return@launch
            }
            runCatching { marketRepository.refreshChart("1.518880", period) }
                .onSuccess { refreshed ->
                    if (refreshed.bars.isNotEmpty()) {
                        bars = refreshed.bars
                        selectedIndex = filteredChartBars(refreshed.bars, period).lastIndex
                        message = if (refreshed.refreshed) "${period.label} · 华安黄金 ETF · ${etfQuote?.displayTime() ?: "公开行情源"}"
                            else "行情源没有返回 K 线，显示最近缓存"
                    } else message = "行情源没有返回 K 线，显示最近缓存"
                }
                .onFailure { message = if (bars.isEmpty()) "黄金 ETF K 线暂时不可用：${it.message ?: "请稍后重试"}" else "更新失败，显示本机缓存" }
            loading = false
        }
    }
    LaunchedEffect(period, refreshRevision) { loadChart(force = refreshRevision > 0) }
    LaunchedEffect(period) {
        if (period == ChartPeriod.INTRADAY) while (isActive) {
            delay(60_000)
            if (!loading) runCatching { marketRepository.refreshChart("1.518880", ChartPeriod.INTRADAY) }
                .getOrNull()?.bars?.takeIf { it.isNotEmpty() }?.let { fresh ->
                    bars = fresh
                    selectedIndex = filteredChartBars(fresh, period).lastIndex
                }
        }
    }
    LaunchedEffect(Unit) {
        onRefresh()
        while (isActive) { delay(30_000); onRefresh() }
    }
    val visibleBars = filteredChartBars(bars, period)
    val selectedBar = visibleBars.getOrNull(selectedIndex.coerceIn(0, (visibleBars.lastIndex).coerceAtLeast(0)))
    ScreenColumn(modifier, spacing = 14.dp, onPullRefresh = { onRefresh(); refreshRevision++ }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MarketBackButton(label = "总览", onClick = onBack)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onRefresh(); refreshRevision++ }, enabled = !loading && !quoteLoading) {
                Text(if (quoteLoading) "更新报价…" else "刷新行情")
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("黄金", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text("国际 XAU/USD · 国内 Au99.99 · 黄金 ETF", color = Muted, fontSize = 12.sp)
        }
        Card(Modifier.fillMaxWidth(), shape = TacticalPanel, colors = CardDefaults.cardColors(containerColor = White),
            border = BorderStroke(1.dp, Color(0xFFD8D8D2)), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("国内现货 · AU9999", color = Muted, fontSize = 11.sp)
                        AnimatedTicker(domesticQuote?.let { "¥${String.format("%.2f", it.price)}" } ?: "—", Ink, 27.sp)
                        Text("人民币 / 克", color = Muted, style = MarketType.caption)
                        Text(
                            "上金所公开现货 · ${domesticQuote?.displayTime() ?: "等待行情"}",
                            color = Blue, style = MarketType.caption, maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("国际现货 · XAU/USD", color = Muted, fontSize = 11.sp)
                        AnimatedTicker(quote?.let { "US\$${String.format("%,.2f", it.price)}" } ?: "—", Ink, 27.sp)
                        Text("美元 / 金衡盎司", color = Muted, style = MarketType.caption)
                        Text(
                            "${quote?.displayTime() ?: "等待行情"} · ${quote?.displaySource() ?: "公共报价源"}",
                            color = Muted, style = MarketType.caption, maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                val premium = if (domesticQuote != null && internationalCnyGramQuote != null) domesticQuote.price - internationalCnyGramQuote.price else null
                val premiumPct = if (premium != null && internationalCnyGramQuote != null && internationalCnyGramQuote.price > 0) premium / internationalCnyGramQuote.price * 100 else null
                val goldPalette = MarketTheme.colors
                val goldGeometry = MarketTheme.geometry
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(goldGeometry.chip))
                        .background(goldPalette.surfaceAlt)
                        .padding(horizontal = goldGeometry.lg, vertical = goldGeometry.md),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text("国内相对国际溢价", color = goldPalette.muted, style = MarketType.label)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            premium?.let { "${String.format("%+.2f", it)} 元" } ?: "—",
                            color = premium?.let { plColor(BigDecimal.valueOf(it)) } ?: goldPalette.muted,
                            style = MarketType.numberLarge
                        )
                        Text(
                            premiumPct?.let { "${String.format("%+.2f", it)}%" } ?: "",
                            color = premiumPct?.let { plColor(BigDecimal.valueOf(it)) } ?: goldPalette.muted,
                            style = MarketType.numberSmall
                        )
                    }
                    Text(
                        "仅此处折算为人民币 / 克；国际金价仍为美元 / 盎司",
                        color = goldPalette.muted, style = MarketType.caption
                    )
                }
                androidx.compose.animation.AnimatedContent(targetState = etfQuote?.price,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "gold_etf_quote") { price ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("华安黄金 ETF · 518880", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text("${etfQuote?.displayTime() ?: "暂无报价"} · ${etfQuote?.displaySource() ?: "等待数据"}", color = Muted, fontSize = 10.sp)
                        }
                        AnimatedTicker(price?.let { "¥${String.format("%.3f", it)}" } ?: "—", Ink, 17.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(etfQuote?.changePct?.asSignedPercent() ?: "", color = etfQuote?.let { plColor(BigDecimal.valueOf(it.changePct)) } ?: Muted, fontSize = 11.sp)
                    }
                }
            }
        }
        if (goldPositions.isNotEmpty()) {
            val goldShape = RoundedCornerShape(MarketTheme.geometry.card)
            Card(
                Modifier.fillMaxWidth().marketShadow(goldShape, MarketElevation.card),
                shape = goldShape,
                colors = CardDefaults.cardColors(containerColor = MarketTheme.colors.surface),
                border = BorderStroke(1.dp, MarketTheme.colors.line)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MarketSectionHeader("我的黄金", caption = "Au99.99 与银行积存金分开计价")
                    goldPositions.forEach { position ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(position.asset.name, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text("持有 ${position.units.portfolioQtyDisplay()} 克 · 成本 ¥${position.cost.portfolioMoneyGrouped()}", color = Muted, fontSize = 10.sp)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    position.value?.let { "市值 ¥${it.portfolioMoney()}" }
                                        ?: if (position.quoteExpired) "报价已过期" else "需录入克价",
                                    color = if (position.quoteExpired) ErrorColor else Ink, fontSize = 11.sp
                                )
                                Text(position.holdingProfit?.let { "盈亏 ${if (it.signum() > 0) "+" else ""}¥${it.portfolioMoney()}" } ?: "估值待补",
                                    color = position.holdingProfit?.let { value -> plColor(value) } ?: Muted, fontSize = 10.sp)
                            }
                        }
                        if (position.quote != null) Text(
                            "${portfolioQuoteSourceLabel(position.quoteSource)} · ${portfolioQuoteTimeLabel(position.quoteTime)}",
                            color = Muted, fontSize = 9.sp
                        )
                    }
                    Text("Au99.99 可按上金所报价计价；银行积存金请录入账户实际克价。国际现货不用于推算银行报价。",
                        color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                }
            }
        }
        val goldHistoryKey = listOf(
            quote?.fetchedAt, domesticQuote?.fetchedAt, internationalCnyGramQuote?.fetchedAt
        )
        val domesticHistory = remember(goldHistoryKey) { ledger.goldDailyHistory("sge_cny_gram") }
        val internationalHistory = remember(goldHistoryKey) { ledger.goldDailyHistory("xau_cny_gram") }
        val premiumHistory = remember(goldHistoryKey) { ledger.goldDailyHistory("premium_cny_gram") }
        GoldTrendCard(domestic = domesticHistory, internationalCny = internationalHistory, premium = premiumHistory)

        val goldCardShape = RoundedCornerShape(MarketTheme.geometry.card)
        Card(
            Modifier.fillMaxWidth().marketShadow(goldCardShape, MarketElevation.card),
            shape = goldCardShape,
            colors = CardDefaults.cardColors(containerColor = MarketTheme.colors.surface),
            border = BorderStroke(1.dp, MarketTheme.colors.line)
        ) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (period == ChartPeriod.INTRADAY) "黄金 ETF 当日分时" else "黄金 ETF K 线",
                        color = Ink, style = MarketType.section, modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (loading) "加载中" else "更新",
                        color = if (loading) MarketTheme.colors.muted else MarketTheme.colors.accent,
                        style = MarketType.label,
                        modifier = Modifier.marketPressable(enabled = !loading) { loadChart(true) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
                Text(
                    if (period == ChartPeriod.INTRADAY) {
                        "默认显示当日 5 分钟分时；黄金报价约每 30 秒刷新，分时约每分钟更新"
                    } else {
                        "518880 作为国内黄金交易代理，价格不等同于现货克价"
                    },
                    color = MarketTheme.colors.muted, style = MarketType.caption
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChartPeriod.entries.forEach { item ->
                        MarketChoiceChip(label = item.label, selected = period == item) { period = item }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MarketChoiceChip(label = "走势", selected = style == MarketChartStyle.LINE) { style = MarketChartStyle.LINE }
                    MarketChoiceChip(label = "K线", selected = style == MarketChartStyle.CANDLE) { style = MarketChartStyle.CANDLE }
                    MarketChoiceChip(label = "MA5", selected = showMa5) { showMa5 = !showMa5 }
                    MarketChoiceChip(label = "MA20", selected = showMa20) { showMa20 = !showMa20 }
                    MarketChoiceChip(label = "成交量", selected = showVolume) { showVolume = !showVolume }
                    MarketChoiceChip(label = "RSI14", selected = showRsi) { showRsi = !showRsi }
                    MarketChoiceChip(label = "MACD", selected = showMacd) { showMacd = !showMacd }
                }
                if (visibleBars.isNotEmpty()) MarketChart(visibleBars, style, showMa5, showMa20, showVolume, selectedIndex,
                    Modifier.fillMaxWidth().height(220.dp), onIndexSelected = { selectedIndex = it })
                else Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    Text(if (loading) "正在加载黄金行情…" else "暂无该周期 K 线", color = Muted, fontSize = 13.sp)
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(visibleBars.firstOrNull()?.let { chartDate(it.day) } ?: "—", color = Muted, fontSize = 9.sp)
                    Text(selectedBar?.let { "${chartDate(it.day)} · 收 ¥${String.format("%.3f", it.close)}" } ?: "拖动图表查看", color = Blue, fontSize = 10.sp)
                    Text(visibleBars.lastOrNull()?.let { chartDate(it.day) } ?: "—", color = Muted, fontSize = 9.sp)
                }
                if (message.isNotBlank()) Text(message, color = Muted, fontSize = 10.sp)
            }
        }
        Button(onClick = onOpenEtf, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("查看 518880 完整行情与指标") }
        if (message.isNotBlank()) Text(message, color = Muted, fontSize = 11.sp)
        Text("国际金价直接显示 XAU/USD 美元 / 金衡盎司报价。国内溢价比较会另按汇率和金衡盎司重量折算为人民币 / 克；ETF 图表仅观察国内交易品种走势。行情可能延迟。", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
    }
}

