package cn.market.assistant

import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.domain.market.MarketRepository
import cn.market.assistant.domain.market.ChartPeriod
import cn.market.assistant.domain.market.MarketOhlcBar
import cn.market.assistant.data.market.MarketRepositoryImpl
import cn.market.assistant.snapshot.DailyPortfolioSnapshotScheduler
import cn.market.assistant.domain.quant.MarketRiskAnalytics
import cn.market.assistant.domain.quant.PriceSeries
import cn.market.assistant.domain.quant.RiskAnalysisResult
import cn.market.assistant.domain.quant.MarketIndicators
import cn.market.assistant.domain.quant.MacdValue
import cn.market.assistant.domain.portfolio.AssetInfo
import cn.market.assistant.domain.portfolio.AssetKind
import cn.market.assistant.domain.portfolio.AccountCashEventType
import cn.market.assistant.domain.portfolio.AccountCashEvent
import cn.market.assistant.domain.portfolio.AccountCashBalance
import cn.market.assistant.domain.portfolio.AccountCashLedger
import cn.market.assistant.domain.portfolio.XirrResult
import cn.market.assistant.domain.portfolio.PeriodFacts
import cn.market.assistant.domain.portfolio.PortfolioAnalytics
import cn.market.assistant.domain.portfolio.PortfolioSnapshot
import cn.market.assistant.domain.portfolio.Position
import cn.market.assistant.domain.portfolio.PositionSnapshot
import cn.market.assistant.domain.portfolio.ReportPeriod
import cn.market.assistant.domain.portfolio.Trade
import cn.market.assistant.domain.portfolio.TradeSide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

private val Paper = Color(0xFFF2F1ED)
private val Ink = Color(0xFF20272A)
private val Muted = Color(0xFF747D80)
private val Blue = Color(0xFF9E6B39)
private val SoftBlue = Color(0xFFF0E8DC)
private val Gold = Color(0xFFB89561)
private val White = Color(0xFFFCFBF7)
private val ErrorColor = Color(0xFFB8424B)
private val MarketUp = Color(0xFFC3444D)
private val MarketDown = Color(0xFF168568)
private val TacticalPanel = RoundedCornerShape(20.dp)
private val LocalOpenDrawer = staticCompositionLocalOf<() -> Unit> { {} }
private const val GOLD_ROUTE = "__gold_dashboard__"
private const val HEALTH_ROUTE = "__data_health__"

private data class HealthCheckSummary(
    val checkedAt: Long,
    val marketStatus: String,
    val goldStatus: String,
    val hasWarnings: Boolean
)

private enum class TrendRange(val label: String, val days: Long?) {
    WEEK("7 日", 7), MONTH("30 日", 30), QUARTER("90 日", 90), ALL("全部", null)
}

private enum class TrendMetric(val label: String) { ASSET("资产规模"), PROFIT("累计盈亏") }

private enum class AllocationBasis(val label: String) { MARKET("按市值"), COST("按成本") }

private enum class WatchlistSort(val label: String) {
    ADDED("加入顺序"), GAINERS("涨幅靠前"), LOSERS("跌幅靠前")
}

private data class TrendPoint(val day: LocalDate, val value: BigDecimal)

private data class AllocationSlice(val id: String, val name: String, val value: BigDecimal, val color: Color)

private enum class Tab(val label: String, val icon: String) {
    HOME("总览", "home"), WATCHLIST("自选", "star"), RECORD("记账", "plus"), ASSETS("持仓", "assets"), ANALYSIS("分析", "chart")
}

private enum class ChartStyle(val label: String) { LINE("走势"), CANDLE("K线") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DailyPortfolioSnapshotScheduler.ensureScheduled(applicationContext)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.statusBarColor = android.graphics.Color.rgb(242, 241, 237)
        window.navigationBarColor = android.graphics.Color.rgb(28, 34, 37)
        val ledger = Ledger(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Blue, onPrimary = Color.White, secondary = Gold,
                background = Paper, surface = White, onSurface = Ink,
                onBackground = Ink, error = ErrorColor,
                secondaryContainer = SoftBlue, onSecondaryContainer = Ink,
                surfaceVariant = Color(0xFFECEBE6), outline = Color(0xFFD6D5CF)
            )) { MarketApp(ledger) }
        }
    }
}

private fun BigDecimal.money() = setScale(2, RoundingMode.HALF_UP).toPlainString()
private fun BigDecimal.qty() = stripTrailingZeros().toPlainString()
private fun plColor(value: BigDecimal) = when (value.signum()) {
    1 -> MarketUp
    -1 -> MarketDown
    else -> Muted
}

@Composable
private fun assetChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = SoftBlue,
    selectedLabelColor = Ink,
    containerColor = White,
    labelColor = Muted
)

@Composable
private fun MarketApp(ledger: Ledger) {
    val marketRepository = remember(ledger) { MarketRepositoryImpl(ledger, Dispatchers.IO) }
    var tab by remember { mutableStateOf(Tab.HOME) }
    var revision by remember { mutableStateOf(0) }
    var marketQuotes by remember { mutableStateOf(emptyMap<String, MarketQuote>()) }
    var marketLoading by remember { mutableStateOf(false) }
    var marketMessage by remember { mutableStateOf("") }
    var goldMessage by remember { mutableStateOf("") }
    var goldLoading by remember { mutableStateOf(false) }
    var selectedMarket by remember { mutableStateOf<String?>(null) }
    var showSectors by remember { mutableStateOf(false) }
    var dataMessage by remember { mutableStateOf("") }
    var dataMessageIsError by remember { mutableStateOf(false) }
    var healthChecking by remember { mutableStateOf(false) }
    var healthCheck by remember { mutableStateOf<HealthCheckSummary?>(null) }
    var restorePayload by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            val output = context.contentResolver.openOutputStream(uri) ?: error("无法创建备份文件")
            output.use { it.write(ledger.exportBackup().toByteArray(Charsets.UTF_8)) }
        }.onSuccess { dataMessage = "账本备份已导出"; dataMessageIsError = false }
            .onFailure { dataMessage = it.message ?: "导出失败"; dataMessageIsError = true }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("无法读取备份文件")
        }.onSuccess { restorePayload = it; dataMessage = "" }
            .onFailure { dataMessage = it.message ?: "读取备份失败"; dataMessageIsError = true }
    }
    val scope = rememberCoroutineScope()
    fun refreshMarket() {
        if (marketLoading) return
        scope.launch {
            marketLoading = true
            runCatching { marketRepository.refreshOverview() }
                .onSuccess { result ->
                    marketQuotes = result.quotes
                    marketMessage = when {
                        result.refreshedCount == 0 -> "行情源未返回数据，显示最近缓存"
                        result.coverageIncomplete -> "部分市场暂时没有可用报价；有数据的市场已显示，恢复后可刷新"
                        else -> ""
                    }
                }.onFailure {
                    marketQuotes = marketRepository.cachedQuotes()
                    marketMessage = if (marketQuotes.isEmpty()) "行情暂不可用，请稍后重试" else "刷新失败，显示本机缓存"
                }
            marketLoading = false
        }
    }
    fun refreshGoldSpot() {
        if (goldLoading) return
        scope.launch {
            goldLoading = true
            runCatching { marketRepository.refreshGold() }
                .onSuccess { result ->
                    marketQuotes = result.quotes
                    goldMessage = when {
                        result.refreshedCount == 0 -> "国内与国际黄金行情暂不可用，显示最近缓存"
                        result.sourceFailureCount > 0 -> "部分金价源暂不可用；每项价格保留独立来源和时间"
                        else -> ""
                    }
                }.onFailure {
                    marketQuotes = marketRepository.cachedQuotes()
                    goldMessage = "金价刷新失败，显示本机缓存"
            }
            goldLoading = false
        }
    }
    fun runHealthCheck() {
        if (healthChecking) return
        scope.launch {
            healthChecking = true
            val marketRequest = async { runCatching { marketRepository.refreshOverview() } }
            val goldRequest = async { runCatching { marketRepository.refreshGold() } }
            val marketResult = marketRequest.await()
            val goldResult = goldRequest.await()
            marketQuotes = runCatching { marketRepository.cachedQuotes() }.getOrDefault(marketQuotes)
            val marketStatus = marketResult.fold(
                onSuccess = { result -> when {
                    result.refreshedCount == 0 -> "没有新行情，继续使用本机缓存"
                    result.coverageIncomplete -> "更新 ${result.refreshedCount} 项，部分预设标的缺少报价"
                    else -> "已更新 ${result.refreshedCount} 项预设行情"
                } },
                onFailure = { "请求失败，继续使用本机缓存" }
            )
            val goldStatus = goldResult.fold(
                onSuccess = { result -> when {
                    result.refreshedCount == 0 -> "没有新金价，继续使用本机缓存"
                    result.sourceFailureCount > 0 -> "更新 ${result.refreshedCount} 项，${result.sourceFailureCount} 个来源暂不可用"
                    else -> "已更新 ${result.refreshedCount} 项黄金行情"
                } },
                onFailure = { "请求失败，继续使用本机缓存" }
            )
            val warning = marketResult.isFailure || goldResult.isFailure ||
                marketResult.getOrNull()?.let { it.refreshedCount == 0 || it.coverageIncomplete } == true ||
                goldResult.getOrNull()?.let { it.refreshedCount == 0 || it.sourceFailureCount > 0 } == true
            healthCheck = HealthCheckSummary(System.currentTimeMillis() / 1000, marketStatus, goldStatus, warning)
            healthChecking = false
        }
    }
    LaunchedEffect(marketRepository) {
        marketQuotes = marketRepository.cachedQuotes()
        refreshMarket()
    }
    val positions = remember(revision) { ledger.positions() }
    val trades = remember(revision) { ledger.trades() }
    val cashEvents = remember(revision) { ledger.accountCashEvents() }
    var snapshotRevision by remember { mutableStateOf(0) }
    LaunchedEffect(positions, cashEvents, trades) {
        withContext(Dispatchers.IO) { ledger.savePortfolioSnapshot(positions) }
        snapshotRevision++
    }
    val recordedSnapshots = remember(snapshotRevision) { ledger.portfolioSnapshots() }
    val portfolioSnapshots = recordedSnapshots.filter { it.valid }
    val invalidatedSnapshotCount = recordedSnapshots.count { !it.valid }
    val validSnapshotDays = portfolioSnapshots.mapTo(mutableSetOf()) { it.day }
    val positionSnapshots = remember(snapshotRevision) { ledger.positionSnapshots() }.filterKeys { it in validSnapshotDays }
    val pending = trades.filter { !it.confirmed && !it.isReversed }
    val totalValue = positions.fold(BigDecimal.ZERO) { sum, p -> sum + (p.value ?: BigDecimal.ZERO) }
    val totalCost = positions.fold(BigDecimal.ZERO) { sum, p -> sum + p.cost }
    val realized = positions.fold(BigDecimal.ZERO) { sum, p -> sum + p.realized }
    val holding = positions.fold(BigDecimal.ZERO) { sum, p -> sum + (p.holdingProfit ?: BigDecimal.ZERO) }
    val cashBalance = remember(cashEvents) { AccountCashLedger.balance(cashEvents) }
    val accountValue = if (cashBalance != null && positions.all { it.value != null }) {
        totalValue + cashBalance.amount
    } else null
    val today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
    val todayFacts = remember(snapshotRevision, revision, today) {
        PortfolioAnalytics.facts(ReportPeriod.DAY, today, portfolioSnapshots, positionSnapshots, trades, cashEvents)
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        scrimColor = Ink.copy(alpha = 0.46f),
        drawerContent = {
            ModalDrawerSheet(
                drawerShape = CutCornerShape(topStart = 0.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 0.dp),
                drawerContainerColor = White
            ) {
                Column(
                    Modifier.fillMaxHeight().padding(horizontal = 22.dp, vertical = 27.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text("MARKET FIELDNOTES", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("市场观察台", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                    Text("行情、板块与个人账本", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(20.dp))
                    val drawerItems = listOf(
                        Triple("总览", Tab.HOME, false),
                        Triple("黄金行情", Tab.HOME, false),
                        Triple("行业板块", Tab.HOME, true),
                        Triple("自选市场", Tab.WATCHLIST, false),
                        Triple("交易记录", Tab.RECORD, false),
                        Triple("资产持仓", Tab.ASSETS, false),
                        Triple("量化分析", Tab.ANALYSIS, false),
                        Triple("数据健康", Tab.HOME, false)
                    )
                    drawerItems.forEachIndexed { index, (label, destination, sectorsRoute) ->
                        val isGold = label == "黄金行情"
                        val isHealth = label == "数据健康"
                        val routeSelected = when {
                            isGold -> selectedMarket == GOLD_ROUTE
                            isHealth -> selectedMarket == HEALTH_ROUTE
                            sectorsRoute -> showSectors && selectedMarket == null
                            else -> !showSectors && selectedMarket == null
                        }
                        val selected = tab == destination && routeSelected
                        NavigationDrawerItem(
                            label = { Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
                            selected = selected,
                            onClick = {
                                tab = destination
                                showSectors = sectorsRoute
                                selectedMarket = when {
                                    isGold -> GOLD_ROUTE
                                    isHealth -> HEALTH_ROUTE
                                    else -> null
                                }
                                scope.launch { drawerState.close() }
                            },
                            icon = { NavigationGlyph(listOf("home", "chart", "chart", "star", "plus", "assets", "analysis", "analysis")[index], selected) },
                            shape = CutCornerShape(topStart = 2.dp, topEnd = 12.dp, bottomEnd = 2.dp, bottomStart = 12.dp),
                            colors = androidx.compose.material3.NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = SoftBlue,
                                selectedIconColor = Ink,
                                selectedTextColor = Ink,
                                unselectedIconColor = Muted,
                                unselectedTextColor = Ink
                            )
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text("行情可能存在延迟，仅供研究参考", color = Muted, fontSize = 10.sp)
                }
            }
        }
    ) {
      CompositionLocalProvider(LocalOpenDrawer provides { scope.launch { drawerState.open() } }) {
      Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = White) {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item; if (item != Tab.WATCHLIST) { selectedMarket = null; showSectors = false } },
                        icon = { NavigationGlyph(item.icon, tab == item) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Ink, selectedTextColor = Ink,
                            indicatorColor = SoftBlue, unselectedIconColor = Muted, unselectedTextColor = Muted
                        )
                    )
                }
            }
        }
    ) { insets ->
        AnimatedContent(
            targetState = Triple(tab, selectedMarket, showSectors),
            transitionSpec = {
                (fadeIn(tween(220)) + scaleIn(tween(260), initialScale = .965f) + slideInHorizontally(tween(260)) { it / 14 }) togetherWith
                    (fadeOut(tween(140)) + scaleOut(tween(180), targetScale = 1.015f))
            },
            label = "tab_transition"
        ) { route ->
            val activeTab = route.first
            when (activeTab) {
                Tab.HOME -> if (route.second == HEALTH_ROUTE) DataHealthScreen(
                    positions = positions,
                    trades = trades,
                    cashBalance = cashBalance,
                    validSnapshots = portfolioSnapshots,
                    invalidatedSnapshotCount = invalidatedSnapshotCount,
                    quotes = marketQuotes,
                    healthChecking = healthChecking,
                    healthCheck = healthCheck,
                    onRunCheck = ::runHealthCheck,
                    modifier = Modifier.padding(insets)
                ) else if (route.second == GOLD_ROUTE) GoldDashboardScreen(
                    quote = marketQuotes[MarketApi.GOLD_SPOT_ID],
                    domesticQuote = marketQuotes["GOLD.SGE.AU9999"],
                    internationalCnyGramQuote = marketQuotes[MarketApi.GOLD_CNY_GRAM_ID],
                    etfQuote = marketQuotes["1.518880"], marketRepository = marketRepository,
                    goldPositions = positions.filter { it.asset.kind == AssetKind.GOLD },
                    message = goldMessage, loading = goldLoading, onRefresh = ::refreshGoldSpot,
                    onOpenEtf = { selectedMarket = "1.518880"; showSectors = false; tab = Tab.WATCHLIST },
                    onBack = { selectedMarket = null }, modifier = Modifier.padding(insets)
                ) else if (route.third) SectorExplorerScreen(
                    marketRepository, marketQuotes, marketLoading, marketMessage, ::refreshMarket,
                    onBack = { showSectors = false },
                    onOpenMarket = { selectedMarket = it; showSectors = false; tab = Tab.WATCHLIST },
                    modifier = Modifier.padding(insets)
                ) else OverviewScreen(
                    positions, pending.size, totalValue, totalCost, holding, realized, accountValue,
                    cashBalance != null, todayFacts, portfolioSnapshots,
                    marketQuotes, marketLoading, marketMessage, ::refreshMarket,
                    onRecord = { tab = Tab.RECORD }, onOpenAssets = { tab = Tab.ASSETS },
                    onOpenWatchlist = { tab = Tab.WATCHLIST },
                    onOpenSectors = { showSectors = true },
                    onOpenGold = { selectedMarket = GOLD_ROUTE; showSectors = false },
                    onOpenMarket = { selectedMarket = it; showSectors = false; tab = Tab.WATCHLIST },
                    modifier = Modifier.padding(insets)
                )
                Tab.WATCHLIST -> {
                    val secid = route.second
                    if (secid == null) WatchlistScreen(
                        ledger, marketQuotes, marketLoading, marketMessage, ::refreshMarket,
                        onToggle = { id, watched -> ledger.setWatched(id, watched); revision++ },
                        onOpen = { selectedMarket = it; showSectors = false }, modifier = Modifier.padding(insets)
                    ) else MarketDetailScreen(
                        secid, marketQuotes[secid], marketRepository,
                        onBack = { selectedMarket = null },
                        onToggle = { watched -> ledger.setWatched(secid, watched); revision++ },
                        isWatched = ledger.watchlist().contains(secid), modifier = Modifier.padding(insets)
                    )
                }
                Tab.RECORD -> RecordScreen(
                    ledger, pending, cashBalance, cashEvents, snackbarHostState, { revision++ }, Modifier.padding(insets)
                )
                Tab.ASSETS -> AssetsScreen(
                    positions, trades, ledger, { revision++ },
                    onExport = { backupLauncher.launch("养基助手账本-${java.time.LocalDate.now()}.json") },
                    onImport = { restoreLauncher.launch(arrayOf("application/json", "text/*")) },
                    dataMessage = dataMessage, dataMessageIsError = dataMessageIsError,
                    modifier = Modifier.padding(insets)
                )
                Tab.ANALYSIS -> AnalysisScreen(
                    positions, trades, cashEvents, totalCost, holding, realized, portfolioSnapshots, positionSnapshots,
                    invalidatedSnapshotCount,
                    onRecordSnapshot = {
                        scope.launch {
                            withContext(Dispatchers.IO) { ledger.savePortfolioSnapshot(positions) }
                            snapshotRevision++
                        }
                    },
                    modifier = Modifier.padding(insets)
                )
            }
        }
        restorePayload?.let { payload ->
            AlertDialog(
                onDismissRequest = { restorePayload = null },
                title = { Text("恢复账本备份？") },
                text = { Text("这会替换本机的流水、自定义标的和手动报价，无法撤销。建议先导出当前账本。备份文件包含交易金额，请妥善保管。") },
                confirmButton = { TextButton(onClick = {
                    runCatching { ledger.restoreBackup(payload) }
                        .onSuccess { revision++; dataMessage = "账本已恢复"; dataMessageIsError = false }
                        .onFailure { dataMessage = it.message ?: "恢复失败"; dataMessageIsError = true }
                    restorePayload = null
                }) { Text("替换并恢复") } },
                dismissButton = { TextButton(onClick = { restorePayload = null }) { Text("取消") } },
                containerColor = White, titleContentColor = Ink, textContentColor = Muted
            )
        }
      }
    }
    }
}

@Composable
private fun NavigationGlyph(name: String, selected: Boolean) {
    val color = if (selected) Ink else Muted
    Canvas(Modifier.size(24.dp)) {
        val scale = size.width / 24f
        fun x(value: Float) = value * scale
        fun y(value: Float) = value * scale
        val line = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        when (name) {
            "home" -> {
                val path = Path().apply {
                    moveTo(x(3.5f), y(10.5f)); lineTo(x(12f), y(3.5f)); lineTo(x(20.5f), y(10.5f))
                    lineTo(x(19f), y(10.5f)); lineTo(x(19f), y(20f)); lineTo(x(5f), y(20f)); lineTo(x(5f), y(10.5f)); close()
                }
                if (selected) drawPath(path, color) else drawPath(path, color, style = line)
                drawLine(if (selected) White else color, Offset(x(10f), y(20f)), Offset(x(10f), y(14f)), 1.7.dp.toPx())
                drawLine(if (selected) White else color, Offset(x(14f), y(20f)), Offset(x(14f), y(14f)), 1.7.dp.toPx())
            }
            "star" -> {
                val path = Path()
                repeat(10) { i ->
                    val radius = if (i % 2 == 0) x(9.5f) else x(4.2f)
                    val angle = Math.PI * i / 5 - Math.PI / 2
                    val px = size.width / 2 + kotlin.math.cos(angle).toFloat() * radius
                    val py = size.height / 2 + kotlin.math.sin(angle).toFloat() * radius
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                if (selected) drawPath(path, color) else drawPath(path, color, style = line)
            }
            "plus" -> {
                drawCircle(color, radius = x(10f), style = line)
                drawLine(color, Offset(x(12f), y(7f)), Offset(x(12f), y(17f)), 2.dp.toPx(), StrokeCap.Round)
                drawLine(color, Offset(x(7f), y(12f)), Offset(x(17f), y(12f)), 2.dp.toPx(), StrokeCap.Round)
            }
            "assets" -> {
                drawRoundRect(color, topLeft = Offset(x(3.5f), y(4f)), size = Size(x(17f), y(16f)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(x(2.5f)), style = line)
                drawLine(color, Offset(x(8f), y(9f)), Offset(x(17f), y(9f)), 1.7.dp.toPx(), StrokeCap.Round)
                drawLine(color, Offset(x(8f), y(13f)), Offset(x(17f), y(13f)), 1.7.dp.toPx(), StrokeCap.Round)
                drawCircle(color, radius = x(1f), center = Offset(x(6f), y(9f)))
                drawCircle(color, radius = x(1f), center = Offset(x(6f), y(13f)))
            }
            else -> {
                drawLine(color, Offset(x(4f), y(20f)), Offset(x(4f), y(4f)), 1.8.dp.toPx(), StrokeCap.Round)
                drawLine(color, Offset(x(4f), y(20f)), Offset(x(21f), y(20f)), 1.8.dp.toPx(), StrokeCap.Round)
                val path = Path().apply {
                    moveTo(x(6f), y(16f)); lineTo(x(10f), y(12f)); lineTo(x(13.5f), y(14f)); lineTo(x(19f), y(7f))
                }
                drawPath(path, color, style = line)
                drawCircle(color, x(1.3f), Offset(x(19f), y(7f)))
            }
        }
    }
}

@Composable
private fun ScreenColumn(
    modifier: Modifier = Modifier,
    spacing: Dp = 17.dp,
    verticalPadding: Dp = 14.dp,
    onPullRefresh: (() -> Unit)? = null,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit
) {
    var pullDistance by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val thresholdPx = with(density) { 64.dp.toPx() }
    val maxPullPx = with(density) { 94.dp.toPx() }
    val animatedPull by animateFloatAsState(pullDistance, tween(110), label = "pull_refresh")
    val connection = remember(scrollState, onPullRefresh, thresholdPx, maxPullPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (onPullRefresh == null || scrollState.value > 0) return Offset.Zero
                if (available.y > 0f) {
                    val accepted = available.y.coerceAtMost(maxPullPx - pullDistance)
                    pullDistance = (pullDistance + accepted).coerceAtMost(maxPullPx)
                    return Offset(0f, accepted)
                }
                if (available.y < 0f && pullDistance > 0f) {
                    val consumed = available.y.coerceAtLeast(-pullDistance)
                    pullDistance = (pullDistance + consumed).coerceAtLeast(0f)
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (pullDistance >= thresholdPx) onPullRefresh?.invoke()
                pullDistance = 0f
                return Velocity.Zero
            }
        }
    }
    Box(modifier.fillMaxSize().nestedScroll(connection)) {
        Column(
            Modifier.fillMaxSize().graphicsLayer { translationY = animatedPull * 0.28f }
                .verticalScroll(scrollState).padding(horizontal = 20.dp, vertical = verticalPadding),
            verticalArrangement = Arrangement.spacedBy(spacing), content = {
                if (onPullRefresh != null && animatedPull > 1f) {
                    Box(Modifier.fillMaxWidth().height(with(density) { animatedPull.toDp() }), contentAlignment = Alignment.Center) {
                        Surface(shape = CircleShape, color = White, shadowElevation = 3.dp) {
                            Text(
                                if (pullDistance >= thresholdPx) "↑ 释放刷新" else "↓ 下拉刷新",
                                Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
                                color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                content()
            }
        )
    }
}

@Composable
private fun BrandHeader(title: String, trailing: String = "") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = Ink, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
        if (trailing.isNotEmpty()) Surface(shape = CircleShape, color = SoftBlue) {
            Text(trailing, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Ink, fontSize = 12.sp)
        }
        DrawerMenuButton()
    }
}

@Composable
private fun DrawerMenuButton() {
    val openDrawer = LocalOpenDrawer.current
    Box(
        Modifier.padding(start = 10.dp).size(40.dp).clip(CutCornerShape(topStart = 2.dp, topEnd = 12.dp, bottomEnd = 2.dp, bottomStart = 12.dp))
            .background(SoftBlue).clickable(onClick = openDrawer)
            .semantics { contentDescription = "打开导航菜单" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 1.6.dp.toPx()
            drawLine(Ink, Offset(1.dp.toPx(), size.height * .25f), Offset(size.width - 1.dp.toPx(), size.height * .25f), stroke, StrokeCap.Round)
            drawLine(Ink, Offset(1.dp.toPx(), size.height * .5f), Offset(size.width * .72f, size.height * .5f), stroke, StrokeCap.Round)
            drawLine(Ink, Offset(1.dp.toPx(), size.height * .75f), Offset(size.width - 1.dp.toPx(), size.height * .75f), stroke, StrokeCap.Round)
        }
    }
}

@Composable
private fun GoldOverviewCard(domestic: MarketQuote?, international: MarketQuote?, etf: MarketQuote?, onOpenGold: () -> Unit) {
    Card(onClick = onOpenGold, modifier = Modifier.fillMaxWidth(), shape = TacticalPanel,
        colors = CardDefaults.cardColors(containerColor = White), border = BorderStroke(1.dp, Color(0xFFD8D8D2)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 5.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("国内 · Au99.99", color = Muted, fontSize = 10.sp)
                        AnimatedTicker(domestic?.let { "¥${String.format("%.2f", it.price)}" } ?: "—", Ink, 18.sp)
                        Text("人民币 / 克", color = Muted, fontSize = 9.sp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("国际现货 · XAU/USD", color = Muted, fontSize = 10.sp)
                        AnimatedTicker(international?.let { "US\$${String.format("%,.2f", it.price)}" } ?: "—", Ink, 17.sp)
                        Text("USD / troy oz · 美元 / 金衡盎司原始报价", color = Muted, fontSize = 9.sp, maxLines = 1)
                    }
                }
                Text("国内按元/克 · 国际按美元/盎司 · ETF ${etf?.changePct?.asSignedPercent() ?: "—"}", color = Muted, fontSize = 10.sp)
            }
            Text("进入行情  ›", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

private fun Double.asSignedPercent() = "${if (this > 0) "+" else ""}${String.format("%.2f", this)}%"

private fun MarketQuote.displaySource(): String = when {
    source.startsWith("gold-api") -> "gold-api.com"
    source == "sge-au9999" -> "上海黄金交易所"
    source == "tencent" -> "腾讯行情"
    else -> source
}

@Composable
private fun AnimatedTicker(value: String, color: Color, fontSize: androidx.compose.ui.unit.TextUnit,
                          fontWeight: FontWeight = FontWeight.SemiBold, modifier: Modifier = Modifier) {
    var previous by remember { mutableStateOf(value) }
    val oldNumber = previous.filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull()
    val newNumber = value.filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull()
    val rising = oldNumber == null || newNumber == null || newNumber >= oldNumber
    Row(modifier, horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        value.forEachIndexed { index, character ->
            val oldCharacter = previous.getOrNull(index)
            if (character.isDigit() && oldCharacter != character) {
                AnimatedContent(
                    targetState = character,
                    transitionSpec = {
                        (fadeIn(tween(110)) + slideInVertically(tween(210)) { height -> if (rising) height else -height }) togetherWith
                            (fadeOut(tween(90)) + slideOutVertically(tween(180)) { height -> if (rising) -height else height })
                    },
                    label = "quote_digit_$index",
                    contentAlignment = Alignment.Center
                ) { shown -> Text(shown.toString(), color = color, fontSize = fontSize, fontWeight = fontWeight, maxLines = 1) }
            } else Text(character.toString(), color = color, fontSize = fontSize, fontWeight = fontWeight, maxLines = 1)
        }
    }
    LaunchedEffect(value) { previous = value }
}

@Composable
private fun GoldDashboardScreen(
    quote: MarketQuote?, domesticQuote: MarketQuote?, internationalCnyGramQuote: MarketQuote?, etfQuote: MarketQuote?, marketRepository: MarketRepository,
    goldPositions: List<Position>, message: String, loading: Boolean, onRefresh: () -> Unit,
    onOpenEtf: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier
) {
    var period by remember { mutableStateOf(ChartPeriod.DAY) }
    var style by remember { mutableStateOf(ChartStyle.CANDLE) }
    var showMa5 by remember { mutableStateOf(true) }
    var showVolume by remember { mutableStateOf(true) }
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
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("‹ 总览", color = Blue) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onRefresh(); refreshRevision++ }, enabled = !loading) { Text("刷新行情") }
            DrawerMenuButton()
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
                        Text("人民币 / 克 · ${domesticQuote?.displayTime() ?: "等待行情"}", color = Muted, fontSize = 10.sp)
                        Text("上金所公开现货", color = Blue, fontSize = 9.sp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("国际现货 · XAU/USD", color = Muted, fontSize = 11.sp)
                        AnimatedTicker(quote?.let { "US\$${String.format("%,.2f", it.price)}" } ?: "—", Ink, 27.sp)
                        Text("美元 / 金衡盎司（USD / troy oz）· ${quote?.displayTime() ?: "等待行情"}", color = Muted, fontSize = 10.sp)
                        Text("来源 ${quote?.displaySource() ?: "公共报价源"}", color = Muted, fontSize = 9.sp)
                    }
                }
                val premium = if (domesticQuote != null && internationalCnyGramQuote != null) domesticQuote.price - internationalCnyGramQuote.price else null
                val premiumPct = if (premium != null && internationalCnyGramQuote != null && internationalCnyGramQuote.price > 0) premium / internationalCnyGramQuote.price * 100 else null
                Row(Modifier.fillMaxWidth().background(Paper, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("国内相对国际溢价", color = Muted, fontSize = 10.sp)
                        Text("仅此处折算为人民币 / 克；上方国际金价仍为美元 / 盎司", color = Muted, fontSize = 9.sp)
                    }
                    Text(premium?.let { "${String.format("%+.2f", it)} 元" } ?: "—",
                        color = premium?.let { plColor(BigDecimal.valueOf(it)) } ?: Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text(premiumPct?.let { "${String.format("%+.2f", it)}%" } ?: "",
                        color = premiumPct?.let { plColor(BigDecimal.valueOf(it)) } ?: Muted, fontSize = 11.sp)
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
            Card(Modifier.fillMaxWidth(), shape = TacticalPanel, colors = CardDefaults.cardColors(containerColor = White),
                border = BorderStroke(1.dp, Color(0xFFD8D8D2))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("我的黄金", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    goldPositions.forEach { position ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(position.asset.name, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text("${position.units.qty()} 克 · 成本 ¥${position.cost.money()}", color = Muted, fontSize = 10.sp)
                            }
                            Text(position.value?.let { "市值 ¥${it.money()}" } ?: "需录入克价", color = Ink, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth(), shape = TacticalPanel, colors = CardDefaults.cardColors(containerColor = White),
            border = BorderStroke(1.dp, Color(0xFFD8D8D2))) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("黄金 ETF K 线", color = Ink, fontWeight = FontWeight.SemiBold)
                        Text("518880 作为国内黄金交易代理，价格不等同于现货克价", color = Muted, fontSize = 10.sp)
                    }
                    TextButton(onClick = { loadChart(true) }, enabled = !loading) { Text(if (loading) "加载中" else "更新") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChartPeriod.entries.forEach { item ->
                        FilterChip(selected = period == item, onClick = { period = item }, label = { Text(item.label, fontSize = 11.sp) }, colors = assetChipColors())
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = style == ChartStyle.LINE, onClick = { style = ChartStyle.LINE }, label = { Text("走势") }, colors = assetChipColors())
                    FilterChip(selected = style == ChartStyle.CANDLE, onClick = { style = ChartStyle.CANDLE }, label = { Text("K线") }, colors = assetChipColors())
                    FilterChip(selected = showMa5, onClick = { showMa5 = !showMa5 }, label = { Text("MA5") }, colors = assetChipColors())
                    FilterChip(selected = showVolume, onClick = { showVolume = !showVolume }, label = { Text("成交量") }, colors = assetChipColors())
                }
                if (visibleBars.isNotEmpty()) MarketChart(visibleBars, style, showMa5, false, showVolume, selectedIndex,
                    Modifier.fillMaxWidth().height(220.dp), onIndexSelected = { selectedIndex = it })
                else Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    Text(if (loading) "正在加载黄金行情…" else "暂无该周期 K 线", color = Muted, fontSize = 13.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(visibleBars.firstOrNull()?.let { chartDate(it.day) } ?: "—", color = Muted, fontSize = 9.sp)
                    Text(selectedBar?.let { "${chartDate(it.day)} · 收 ¥${String.format("%.3f", it.close)}" } ?: "拖动图表查看", color = Blue, fontSize = 10.sp)
                    Text(visibleBars.lastOrNull()?.let { chartDate(it.day) } ?: "—", color = Muted, fontSize = 9.sp)
                }
                if (message.isNotBlank()) Text(message, color = Muted, fontSize = 10.sp)
            }
        }
        Button(onClick = onOpenEtf, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text("查看 518880 完整行情与指标") }
        if (message.isNotBlank()) Text(message, color = Muted, fontSize = 11.sp)
        Text("国际金价直接显示 XAU/USD 美元 / 金衡盎司报价。国内溢价比较会另按汇率和金衡盎司重量折算为人民币 / 克；ETF 图表仅观察国内交易品种走势。行情可能延迟。", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun OverviewScreen(
    positions: List<Position>, pendingCount: Int,
    total: BigDecimal, cost: BigDecimal, holding: BigDecimal, realized: BigDecimal,
    accountValue: BigDecimal?, cashTrackingEnabled: Boolean,
    todayFacts: PeriodFacts, snapshots: List<PortfolioSnapshot>,
    marketQuotes: Map<String, MarketQuote>, marketLoading: Boolean, marketMessage: String, onRefreshMarket: () -> Unit,
    onRecord: () -> Unit, onOpenAssets: () -> Unit, onOpenWatchlist: () -> Unit, onOpenSectors: () -> Unit, onOpenGold: () -> Unit,
    onOpenMarket: (String) -> Unit, modifier: Modifier = Modifier
) {
    val quoteCount = positions.count { it.quote != null }
    val allPositionsPriced = quoteCount == positions.size
    val noPositionQuotes = positions.isNotEmpty() && quoteCount == 0
    val accountPeriod = todayFacts.opening?.accountComplete == true && todayFacts.closing?.accountComplete == true
    val todayProfit = if (accountPeriod) todayFacts.accountProfit else todayFacts.profit
    val todayRate = if (accountPeriod) todayFacts.accountModifiedDietzPct else todayFacts.modifiedDietzPct
    val todayMissingReason = if (accountPeriod) todayFacts.accountMissingReason else todayFacts.missingReason
    val latestQuoteAt = positions.mapNotNull { it.quoteTime }.maxOrNull()?.let { value ->
        runCatching {
            java.time.Instant.parse(value).atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        }.getOrDefault(value.take(16).replace('T', ' '))
    }
    ScreenColumn(modifier, onPullRefresh = { if (!marketLoading) onRefreshMarket() }) {
        BrandHeader("资产")
        Card(
            Modifier.fillMaxWidth(), shape = TacticalPanel,
            colors = CardDefaults.cardColors(containerColor = White),
            border = BorderStroke(1.dp, Color(0xFFD8D8D2)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            when {
                                accountValue != null -> "账户总资产 · 含现金"
                                cashTrackingEnabled -> "账户总资产 · 暂不可用"
                                else -> "持仓市值 · 不含现金"
                            },
                            color = Muted, fontSize = 12.sp
                        )
                        Text(
                            when {
                                positions.isEmpty() && accountValue == null -> "尚未录入持仓"
                                noPositionQuotes -> "—"
                                else -> "¥${(accountValue ?: total).money()}"
                            },
                            color = Ink, fontSize = 34.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                    TextButton(onClick = onRecord) { Text("＋ 记一笔") }
                }
                Text(
                    when {
                        accountValue != null -> "现金跟踪已启用，所有持仓均有报价"
                        cashTrackingEnabled && noPositionQuotes -> "当前持仓暂无报价，账户总资产暂不可用"
                        cashTrackingEnabled && !allPositionsPriced -> "部分持仓缺少报价，账户总资产暂不可用"
                        cashTrackingEnabled -> "账户总额暂不可用，检查持仓报价后重试"
                        noPositionQuotes -> "当前持仓暂无报价，持仓市值暂不可用"
                        !allPositionsPriced -> "部分持仓缺少报价，金额仅统计有报价的持仓"
                        else -> "尚未启用现金跟踪；当前金额只包含基金和黄金持仓"
                    },
                    color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (accountPeriod) "今日账户盈亏" else "今日持仓盈亏", color = Muted, fontSize = 10.sp)
                        Text(
                            todayProfit?.let { "${if (it.signum() > 0) "+" else ""}¥${it.money()}" } ?: "暂不可用",
                            color = todayProfit?.let(::plColor) ?: Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            todayRate?.let { "${if (it.signum() > 0) "+" else ""}${it.toPlainString()}%" }
                                ?: todayMissingReason ?: "需完整的昨日与今日快照",
                            color = Muted, fontSize = 9.sp, maxLines = 2, lineHeight = 12.sp
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("持有浮盈", color = Muted, fontSize = 10.sp)
                        Text(
                            if (allPositionsPriced) "${if (holding.signum() > 0) "+" else ""}¥${holding.money()}" else "暂不可用",
                            color = if (allPositionsPriced) plColor(holding) else Muted,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text("成本 ¥${cost.money()}", color = Muted, fontSize = 9.sp, maxLines = 1)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("已实现盈亏", color = Muted, fontSize = 10.sp)
                        Text(
                            "${if (realized.signum() > 0) "+" else ""}¥${realized.money()}",
                            color = plColor(realized), fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text("按已确认流水", color = Muted, fontSize = 9.sp, maxLines = 1)
                    }
                }
                Text(
                    when {
                        positions.isEmpty() -> "暂无持仓报价"
                        latestQuoteAt != null -> "报价覆盖 $quoteCount/${positions.size} 项 · 最近持仓报价 $latestQuoteAt"
                        else -> "报价覆盖 $quoteCount/${positions.size} 项 · 尚无报价时间"
                    },
                    color = Muted, fontSize = 10.sp
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenAssets, modifier = Modifier.weight(1f)) { Text("全部持仓 →") }
                    TextButton(onClick = onOpenGold, modifier = Modifier.weight(1f)) { Text("黄金行情 →") }
                    TextButton(onClick = onOpenWatchlist, modifier = Modifier.weight(1f)) { Text("市场行情 →") }
                }
            }
        }
        if (snapshots.size > 1) {
            Card(
                Modifier.fillMaxWidth(), shape = TacticalPanel,
                colors = CardDefaults.cardColors(containerColor = White),
                border = BorderStroke(1.dp, Color(0xFFD8D8D2))
            ) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("资产变化", Modifier.weight(1f), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("${snapshots.size} 个观察日", color = Muted, fontSize = 10.sp)
                    }
                    PortfolioProfitSparkline(snapshots, Modifier.fillMaxWidth().height(64.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("我的持仓", Modifier.weight(1f), color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onOpenAssets) { Text("全部资产 →") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallStat("基金", "${positions.count { it.asset.kind == AssetKind.FUND }} 只", Modifier.weight(1f))
            SmallStat("黄金", "${positions.count { it.asset.kind == AssetKind.GOLD }} 项", Modifier.weight(1f))
        }
        if (positions.isEmpty()) {
            EmptyCard("先记录一笔基金或黄金交易", "保存交易后，这里会显示份额、成本、最新估值与盈亏。")
            Button(
                onClick = onRecord, modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue)
            ) { Text("＋ 记一笔", fontWeight = FontWeight.SemiBold) }
        } else {
            positions.take(5).forEach { PositionCard(it) }
        }
        if (pendingCount > 0) InfoCard("有 $pendingCount 笔交易待确认", "净值公布后补录确认净值，即可计入持仓。", Blue)
        if (pendingCount > 0 || positions.isNotEmpty()) {
            TextButton(onClick = onRecord, modifier = Modifier.fillMaxWidth()) { Text("记下买入或卖出") }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("行情观察", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                val latest = marketQuotes.values.maxOfOrNull { it.fetchedAt }
                Text(
                    if (latest == null) "行情暂未更新" else "行情截至 ${java.time.Instant.ofEpochSecond(latest).atZone(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))}",
                    color = Muted, fontSize = 10.sp
                )
            }
            TextButton(onClick = onRefreshMarket, enabled = !marketLoading) { Text(if (marketLoading) "刷新中" else "刷新") }
            TextButton(onClick = onOpenSectors) { Text("板块 →") }
            TextButton(onClick = onOpenWatchlist) { Text("自选 →") }
        }
        GoldOverviewCard(marketQuotes["GOLD.SGE.AU9999"], marketQuotes[MarketApi.GOLD_SPOT_ID], marketQuotes["1.518880"], onOpenGold = onOpenGold)
        SectorTickerMarquee(marketQuotes, onOpenMarket)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("全球大盘指数", Modifier.weight(1f), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        if (marketMessage.isNotBlank()) Text(marketMessage, color = Muted, fontSize = 11.sp)
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            MarketInstrumentCatalog.overview.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    pair.forEach { instrument ->
                        MarketIndexCard(instrument, marketQuotes[instrument.secid], Modifier.weight(1f)) { onOpenMarket(instrument.secid) }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SectorTickerMarquee(quotes: Map<String, MarketQuote>, onOpenMarket: (String) -> Unit) {
    val sectors = MarketInstrumentCatalog.sectors
    val density = LocalDensity.current
    val offset = remember { Animatable(0f) }
    val cardWidth = 134.dp
    val cycleWidthPx = with(density) { (sectors.size * cardWidth.value).dp.toPx() }
    LaunchedEffect(cycleWidthPx, sectors.size) {
        // Two identical rows meet seamlessly, so the industry cards never jump at the loop boundary.
        val pixelsPerSecond = with(density) { 18.dp.toPx() }
        val duration = ((cycleWidthPx / pixelsPerSecond) * 1_000).roundToInt().coerceAtLeast(1_000)
        while (isActive) {
            offset.animateTo(cycleWidthPx, tween(duration, easing = LinearEasing))
            offset.snapTo(0f)
        }
    }
    Card(Modifier.fillMaxWidth(), shape = TacticalPanel, colors = CardDefaults.cardColors(containerColor = White),
        border = BorderStroke(1.dp, Color(0xFFD8D8D2))) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("主题板块", Modifier.weight(1f), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("A 股行业 ETF · 匀速轮播", color = Muted, fontSize = 10.sp)
            }
            Text("半导体  /  游戏  /  人工智能  /  通信设备", color = Muted, fontSize = 10.sp)
            Box(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp))) {
                SectorTickerRow(sectors, quotes, onOpenMarket,
                    Modifier.requiredWidth((sectors.size * cardWidth.value).dp).fillMaxHeight()
                        .graphicsLayer { translationX = -offset.value })
                SectorTickerRow(sectors, quotes, onOpenMarket,
                    Modifier.requiredWidth((sectors.size * cardWidth.value).dp).fillMaxHeight()
                        .graphicsLayer { translationX = cycleWidthPx - offset.value })
                Box(Modifier.align(Alignment.CenterStart).width(9.dp).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(White, Color.Transparent))))
                Box(Modifier.align(Alignment.CenterEnd).width(9.dp).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, White))))
            }
        }
    }
}

@Composable
private fun SectorTickerRow(
    sectors: List<MarketSector>, quotes: Map<String, MarketQuote>, onOpenMarket: (String) -> Unit, modifier: Modifier = Modifier
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        sectors.forEach { sector ->
            val quote = quotes[sector.instrumentId]
            Card(onClick = { onOpenMarket(sector.instrumentId) }, modifier = Modifier.width(126.dp).height(62.dp),
                shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Paper),
                border = BorderStroke(1.dp, Color(0xFFE3E1DA))) {
                Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(sector.name, Modifier.weight(1f), color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("ETF ${MarketInstrumentCatalog.byId[sector.instrumentId]?.secid?.substringAfter('.') ?: ""}",
                            color = Muted, fontSize = 8.sp, maxLines = 1)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AnimatedTicker(quote?.let { String.format("%.3f", it.price) } ?: "等待报价", Ink, 12.sp,
                            modifier = Modifier.weight(1f))
                        AnimatedTicker(quote?.changePct?.asSignedPercent() ?: "—",
                            quote?.let { plColor(BigDecimal.valueOf(it.changePct)) } ?: Muted,
                            10.sp, FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketIndexCard(instrument: MarketInstrument, quote: MarketQuote?, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = White), border = BorderStroke(1.dp, Color(0xFFD8D8D2)), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 5.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(instrument.name, color = Muted, fontSize = 11.sp, maxLines = 1)
                Text(when {
                    quote == null -> instrument.market
                    quote.source == "daily-close" -> "近收 ${quote.displayTime()}"
                    else -> instrument.market
                }, color = Muted, fontSize = 9.sp, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AnimatedTicker(quote?.price?.let { String.format("%.2f", it) } ?: "—", Ink, 15.sp)
                AnimatedTicker(quote?.let { it.changePct.asSignedPercent() } ?: "暂无数据",
                    quote?.let { plColor(BigDecimal.valueOf(it.changePct)) } ?: Muted, 10.sp, FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun WatchlistScreen(
    ledger: Ledger, quotes: Map<String, MarketQuote>, loading: Boolean, message: String,
    onRefresh: () -> Unit, onToggle: (String, Boolean) -> Unit, onOpen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("market_ui_preferences", android.content.Context.MODE_PRIVATE)
    }
    var sortMode by remember(preferences) {
        val savedSort = preferences.getString("watchlist_sort", WatchlistSort.ADDED.name)
        mutableStateOf(WatchlistSort.entries.firstOrNull { it.name == savedSort } ?: WatchlistSort.ADDED)
    }
    var query by remember { mutableStateOf("") }
    var marketFilter by remember { mutableStateOf("全部") }
    val watchedOrder = ledger.watchlist()
    val watched = watchedOrder.toSet()
    val instruments = watchedOrder.mapNotNull(MarketInstrumentCatalog.byId::get)
    val addedOrder = watchedOrder.withIndex().associate { it.value to it.index }
    val sortedInstruments = remember(instruments, quotes, sortMode) {
        instruments.sortedWith(when (sortMode) {
            WatchlistSort.ADDED -> compareBy<MarketInstrument> { addedOrder[it.secid] ?: Int.MAX_VALUE }
                .thenBy { it.name }
            WatchlistSort.GAINERS -> compareBy<MarketInstrument> {
                quotes[it.secid]?.changePct?.let { change -> -change } ?: Double.POSITIVE_INFINITY
            }.thenBy { it.name }
            WatchlistSort.LOSERS -> compareBy<MarketInstrument> {
                quotes[it.secid]?.changePct ?: Double.POSITIVE_INFINITY
            }.thenBy { it.name }
        })
    }
    val now = System.currentTimeMillis() / 1000
    val freshQuotes = instruments.mapNotNull { quotes[it.secid] }.filter { it.isFreshAt(now) }
    val advancing = freshQuotes.count { it.changePct > 0.000001 }
    val declining = freshQuotes.count { it.changePct < -0.000001 }
    val unchanged = freshQuotes.size - advancing - declining
    val latestQuote = freshQuotes.maxByOrNull { it.quoteTime }
        ?: instruments.mapNotNull { quotes[it.secid] }.maxByOrNull { it.quoteTime }
    val candidates = MarketInstrumentCatalog.items.filter { instrument ->
        instrument.secid !in watched && (marketFilter == "全部" || instrument.market == marketFilter) &&
            (query.isBlank() || instrument.name.contains(query.trim(), ignoreCase = true))
    }
    ScreenColumn(modifier, onPullRefresh = { if (!loading) onRefresh() }) {
        BrandHeader("自选", "${instruments.size} 个")
        if (instruments.isNotEmpty()) Card(
            Modifier.fillMaxWidth().animateContentSize(tween(240)),
            shape = TacticalPanel,
            colors = CardDefaults.cardColors(containerColor = Ink),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("自选市场广度", color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            latestQuote?.let { quote ->
                                "${freshQuotes.size}/${instruments.size} 个有效报价 · " +
                                    "最近时点 ${quote.displayTime()}"
                            } ?: "${instruments.size} 个关注标的 · 等待行情刷新",
                            color = Color(0xFFBBC1C1), fontSize = 10.sp
                        )
                    }
                    Text(
                        "${freshQuotes.size}/${instruments.size}", color = White,
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                if (freshQuotes.isEmpty()) {
                    Text(
                        "收到有效报价后，这里会汇总自选标的的涨跌分布。",
                        color = Color(0xFFBBC1C1), fontSize = 11.sp
                    )
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("上涨 $advancing", Modifier.weight(1f), color = Color(0xFFFFB1B5), fontSize = 11.sp)
                        Text("持平 $unchanged", Modifier.weight(1f), color = Color(0xFFD0D3D0), fontSize = 11.sp)
                        Text("下跌 $declining", color = Color(0xFF83D1B9), fontSize = 11.sp)
                    }
                    Row(
                        Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Color(0xFF465057))
                            .semantics {
                                contentDescription = "自选市场广度：" +
                                    "上涨 $advancing 个，持平 $unchanged 个，下跌 $declining 个，" +
                                    "共 ${freshQuotes.size} 个有效报价"
                            }
                    ) {
                        if (advancing > 0) Box(
                            Modifier.weight(advancing.toFloat()).fillMaxHeight().background(MarketUp)
                        )
                        if (unchanged > 0) Box(
                            Modifier.weight(unchanged.toFloat()).fillMaxHeight().background(Color(0xFF9DA5A3))
                        )
                        if (declining > 0) Box(
                            Modifier.weight(declining.toFloat()).fillMaxHeight().background(MarketDown)
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("关注指数和市场标的", Modifier.weight(1f), color = Muted, fontSize = 12.sp)
            TextButton(onClick = onRefresh, enabled = !loading) { Text(if (loading) "刷新中" else "刷新行情") }
        }
        if (message.isNotBlank()) Text(message, color = Muted, fontSize = 11.sp)
        if (instruments.isEmpty()) EmptyCard(
            "自选还是空的", "从下方添加指数或股票，之后点列表项目查看走势图。"
        )
        if (instruments.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                WatchlistSort.entries.forEach { option ->
                    FilterChip(
                        selected = sortMode == option,
                        onClick = {
                            sortMode = option
                            preferences.edit().putString("watchlist_sort", option.name).apply()
                        },
                        label = { Text(option.label) }, colors = assetChipColors()
                    )
                }
            }
            AnimatedContent(
                targetState = sortedInstruments,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                label = "watchlist_order"
            ) { visibleInstruments ->
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    visibleInstruments.forEach { instrument ->
                        val quote = quotes[instrument.secid]
                        Card(
                            onClick = { onOpen(instrument.secid) }, modifier = Modifier.fillMaxWidth(),
                            shape = CutCornerShape(
                                topStart = 2.dp, topEnd = 15.dp, bottomEnd = 2.dp, bottomStart = 15.dp
                            ),
                            colors = CardDefaults.cardColors(containerColor = White),
                            border = BorderStroke(1.dp, Color(0xFFD8D8D2))) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                                    ) {
                                        Text(instrument.name, color = Ink, fontWeight = FontWeight.SemiBold)
                                        Text(instrument.market, color = Blue, fontSize = 10.sp)
                                    }
                                    Text(
                                        "${quote?.let {
                                            if (it.source == "daily-close") "近收 ${it.displayTime()}"
                                            else "行情 ${it.displayTime()}"
                                        } ?: "暂无报价"}  ·  点开看日线",
                                        color = Muted, fontSize = 11.sp
                                    )
                                }
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        quote?.let { String.format("%.2f", it.price) } ?: "—",
                                        color = Ink, fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        quote?.let {
                                            "${if (it.changePct > 0) "+" else ""}" +
                                                "${String.format("%.2f", it.changePct)}%"
                                        } ?: "等待行情",
                                        color = quote?.let { plColor(BigDecimal.valueOf(it.changePct)) } ?: Muted,
                                        fontSize = 11.sp
                                    )
                                }
                                TextButton(onClick = { onToggle(instrument.secid, false) }) {
                                    Text("移除", color = Muted)
                                }
                            }
                        }
                    }
                }
            }
        }
        Text("添加关注", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("按名称搜索指数或股票") }, singleLine = true,
            shape = RoundedCornerShape(15.dp), colors = fieldColors()
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("全部", "A股", "美股", "日股", "韩股", "港股").forEach { market ->
                FilterChip(selected = marketFilter == market, onClick = { marketFilter = market },
                    label = { Text(market) }, colors = assetChipColors())
            }
        }
        if (candidates.isEmpty()) EmptyCard("没有匹配标的", "试试名称关键词，或切换市场分类。")
        candidates.forEach { instrument ->
            Card(Modifier.fillMaxWidth(), shape = CutCornerShape(3.dp),
                colors = CardDefaults.cardColors(containerColor = White), border = BorderStroke(1.dp, Color(0xFFD8D8D2))) {
                Row(Modifier.padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(instrument.name, color = Ink, fontWeight = FontWeight.Medium)
                        Text(instrument.market, color = Muted, fontSize = 11.sp)
                    }
                    TextButton(onClick = { onToggle(instrument.secid, true) }) { Text("＋ 关注") }
                }
            }
        }
        Text("报价与日线缓存在本机；行情源不可用时会显示最近缓存及其时间。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun DataHealthScreen(
    positions: List<Position>,
    trades: List<Trade>,
    cashBalance: AccountCashBalance?,
    validSnapshots: List<PortfolioSnapshot>,
    invalidatedSnapshotCount: Int,
    quotes: Map<String, MarketQuote>,
    healthChecking: Boolean,
    healthCheck: HealthCheckSummary?,
    onRunCheck: () -> Unit,
    modifier: Modifier = Modifier
) {
    val now = System.currentTimeMillis() / 1000
    val supported = MarketInstrumentCatalog.items
    val cachedCount = supported.count { it.secid in quotes }
    val missing = supported.filter { it.secid !in quotes }
    val stale = supported.mapNotNull { instrument -> quotes[instrument.secid]?.let { instrument to it } }
        .filter { (_, quote) -> !quote.isFreshAt(now) }
    val freshCount = cachedCount - stale.size
    val confirmedCount = trades.count { it.confirmed && !it.isReversed }
    val pendingCount = trades.count { !it.confirmed && !it.isReversed }
    val reversedCount = trades.count { it.isReversed }
    val pricedPositions = positions.count { it.value != null }
    val sourceGroups = quotes.values.groupBy { it.source }.entries.sortedByDescending { (_, values) ->
        values.maxOfOrNull { it.quoteTime } ?: 0L
    }

    ScreenColumn(modifier, spacing = 14.dp, onPullRefresh = onRunCheck) {
        BrandHeader("数据健康", "本机诊断")
        Card(
            Modifier.fillMaxWidth(), shape = TacticalPanel,
            colors = CardDefaults.cardColors(containerColor = Ink),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("行情与账本自检", color = White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            healthCheck?.let { "上次检查 ${healthCheck.checkedAt.healthTime()}" } ?: "检查公共行情源，并读取本机账本状态",
                            color = Color(0xFFBBC1C1), fontSize = 11.sp
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = when {
                            healthChecking || healthCheck == null -> Color(0xFF465057)
                            healthCheck.hasWarnings -> Color(0xFF6B5230)
                            else -> Color(0xFF245C4D)
                        }
                    ) {
                        Text(
                            when {
                                healthChecking -> "检查中"
                                healthCheck == null -> "未检查"
                                healthCheck.hasWarnings -> "有缺口"
                                else -> "已更新"
                            },
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            color = White, fontSize = 10.sp, fontWeight = FontWeight.Medium
                        )
                    }
                }
                healthCheck?.let { check ->
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("市场行情  ·  ${check.marketStatus}", color = Color(0xFFE0E3E1), fontSize = 11.sp, lineHeight = 16.sp)
                        Text("黄金行情  ·  ${check.goldStatus}", color = Color(0xFFE0E3E1), fontSize = 11.sp, lineHeight = 16.sp)
                    }
                } ?: Text(
                    "行情缓存会显示来源时间；自检后可看到本次刷新结果。",
                    color = Color(0xFFBBC1C1), fontSize = 11.sp, lineHeight = 16.sp
                )
                Button(
                    onClick = onRunCheck,
                    enabled = !healthChecking,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SoftBlue, contentColor = Ink)
                ) { Text(if (healthChecking) "正在检查…" else "立即自检", fontWeight = FontWeight.SemiBold) }
            }
        }

        Text("数据完整性", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Card(
            Modifier.fillMaxWidth(), shape = TacticalPanel,
            colors = CardDefaults.cardColors(containerColor = White),
            border = BorderStroke(1.dp, Color(0xFFD8D8D2))
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                DataHealthRow(
                    "预设行情覆盖",
                    "$cachedCount / ${supported.size} 个标的有缓存",
                    when {
                        supported.isEmpty() -> "暂无预设标的"
                        missing.isNotEmpty() -> "缺少：${missing.take(3).joinToString("、") { it.name }}${if (missing.size > 3) "等 ${missing.size} 项" else ""}"
                        else -> "预设标的均有行情缓存"
                    },
                    if (missing.isEmpty()) MarketDown else Gold
                )
                DataHealthRow(
                    "报价时点",
                    "$freshCount 项在 72 小时窗口内",
                    if (stale.isEmpty()) "没有超出 72 小时的预设行情" else
                        "已过期：${stale.take(3).joinToString("、") { it.first.name }}${if (stale.size > 3) "等 ${stale.size} 项" else ""}",
                    if (stale.isEmpty()) MarketDown else Gold
                )
                DataHealthRow(
                    "持仓估值",
                    if (positions.isEmpty()) "暂无持仓" else "$pricedPositions / ${positions.size} 项有价格",
                    if (positions.isEmpty()) "录入交易后会在这里核对持仓报价" else
                        if (pricedPositions == positions.size) "所有当前持仓都有可用估值" else "缺少价格的持仓不会计入总市值",
                    if (positions.isEmpty() || pricedPositions == positions.size) Muted else Gold
                )
            }
        }

        Text("本机账本", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Card(
            Modifier.fillMaxWidth(), shape = TacticalPanel,
            colors = CardDefaults.cardColors(containerColor = White),
            border = BorderStroke(1.dp, Color(0xFFD8D8D2))
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                DataHealthRow("有效流水", "$confirmedCount 笔已确认 · $pendingCount 笔待确认", "$reversedCount 笔冲正记录保留审计")
                DataHealthRow(
                    "现金跟踪",
                    cashBalance?.let { "¥${it.amount.money()}" } ?: "未启用",
                    if (cashBalance == null) "启用后会校验余额并纳入账户总资产" else "当前现金余额通过非负校验"
                )
                DataHealthRow(
                    "组合观察",
                    "${validSnapshots.size} 个有效观察日",
                    if (invalidatedSnapshotCount == 0) "没有因历史更正停用的快照" else "$invalidatedSnapshotCount 个旧观察日已停用并排除分析",
                    if (invalidatedSnapshotCount == 0) Muted else Gold
                )
            }
        }

        Text("行情来源", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (sourceGroups.isEmpty()) EmptyCard("暂无行情缓存", "联网自检成功后，这里会显示行情来源和报价时点。")
        sourceGroups.forEach { (source, sourceQuotes) ->
            val latest = sourceQuotes.maxByOrNull { it.quoteTime }
            Card(
                Modifier.fillMaxWidth(), shape = CutCornerShape(topStart = 2.dp, topEnd = 14.dp, bottomEnd = 2.dp, bottomStart = 14.dp),
                colors = CardDefaults.cardColors(containerColor = White),
                border = BorderStroke(1.dp, Color(0xFFD8D8D2))
            ) {
                Row(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(source.displayHealthSource(), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("${sourceQuotes.size} 项 · ${latest?.displayTime() ?: "报价时点未知"}", color = Muted, fontSize = 11.sp)
                    }
                    Text(
                        if (sourceQuotes.all { it.isFreshAt(now) }) "时点正常" else "有旧报价",
                        color = if (sourceQuotes.all { it.isFreshAt(now) }) MarketDown else Gold,
                        fontSize = 10.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        InfoCard(
            "检查范围",
            "此页检查本机账本、行情缓存及本次手动自检结果。连续 20 个交易日覆盖率、接口历史成功率和系统权限状态尚未接入。",
            SoftBlue
        )
    }
}

@Composable
private fun DataHealthRow(label: String, value: String, detail: String, detailColor: Color = Muted) {
    Column(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
            Text(value, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(detail, color = detailColor, fontSize = 10.sp, lineHeight = 15.sp)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE9E8E2)))
}

private fun MarketQuote.isFreshAt(now: Long): Boolean =
    quoteTime <= now + 5 * 60 && now - quoteTime <= 72 * 60 * 60

private fun String.displayHealthSource(): String = when (this) {
    "eastmoney" -> "东方财富"
    "tencent" -> "腾讯行情"
    "daily-close" -> "日线收盘价"
    "sge-au9999" -> "上海黄金交易所 · Au99.99"
    "gold-api-xau-usd" -> "Gold API · XAU/USD"
    "gold-api-xau-cny" -> "Gold API · 人民币折算"
    else -> this
}

private fun Long.healthTime(): String = runCatching {
    java.time.Instant.ofEpochSecond(this).atZone(java.time.ZoneId.of("Asia/Shanghai"))
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}.getOrDefault("时间未知")

@Composable
private fun SectorExplorerScreen(
    marketRepository: MarketRepository, quotes: Map<String, MarketQuote>, loading: Boolean, message: String,
    onRefresh: () -> Unit, onBack: () -> Unit, onOpenMarket: (String) -> Unit, modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("全部") }
    var descending by remember { mutableStateOf(true) }
    var autoRefresh by remember { mutableStateOf(true) }
    var autoPlay by remember { mutableStateOf(true) }
    var refreshRevision by remember { mutableStateOf(0) }
    var bars by remember { mutableStateOf(emptyMap<String, List<MarketOhlcBar>>()) }
    val scope = rememberCoroutineScope()
    fun refreshCharts(force: Boolean) {
        scope.launch {
            MarketInstrumentCatalog.sectors.forEach { sector ->
                val cached = runCatching { marketRepository.cachedChart(sector.instrumentId, ChartPeriod.DAY) }.getOrNull()
                cached?.let { bars = bars + (sector.instrumentId to it.bars) }
                if (!force && cached != null && cached.cachedAt > 0 &&
                    System.currentTimeMillis() - cached.cachedAt < 12 * 60 * 60 * 1000L) return@forEach
                runCatching { marketRepository.refreshChart(sector.instrumentId, ChartPeriod.DAY) }
                    .getOrNull()?.let { refreshed -> bars = bars + (sector.instrumentId to refreshed.bars) }
            }
        }
    }
    LaunchedEffect(refreshRevision) { refreshCharts(force = refreshRevision > 0) }
    LaunchedEffect(autoRefresh) {
        if (autoRefresh) while (isActive) {
            delay(30_000)
            onRefresh()
        }
    }
    val sectors = MarketInstrumentCatalog.sectors.filter { sector ->
        (category == "全部" || sector.category == category) &&
            (query.isBlank() || sector.name.contains(query.trim(), true) || sector.category.contains(query.trim(), true) ||
                MarketInstrumentCatalog.byId[sector.instrumentId]?.name?.contains(query.trim(), true) == true)
    }.sortedWith(compareBy<MarketSector> { quotes[it.instrumentId]?.changePct?.let { pct -> if (descending) -pct else pct } ?: Double.POSITIVE_INFINITY })
    val riskResult = remember(bars, sectors) {
        MarketRiskAnalytics.equalWeight(sectors.map { sector ->
            PriceSeries(sector.id, sector.name, bars[sector.instrumentId].orEmpty()
                .associate { bar -> bar.day to bar.close })
        })
    }

    ScreenColumn(modifier, spacing = 13.dp, onPullRefresh = { if (!loading) { onRefresh(); refreshRevision++ } }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("‹ 总览", color = Blue) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onRefresh(); refreshRevision++ }, enabled = !loading) {
                Text(if (loading) "刷新中" else "刷新全部")
            }
            DrawerMenuButton()
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("行业板块", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Text("用代表性 ETF 观察方向与强弱", color = Muted, fontSize = 12.sp)
            }
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFF0E8DC)) {
                Text("A股", Modifier.padding(horizontal = 11.dp, vertical = 7.dp), color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
        Text("板块观察 · ${sectors.size} 个代表 ETF", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        SectorRiskCard(riskResult)
        Card(Modifier.fillMaxWidth(), shape = TacticalPanel, colors = CardDefaults.cardColors(containerColor = White),
            border = BorderStroke(1.dp, Color(0xFFD8D8D2))) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("强弱排序", color = Muted, fontSize = 11.sp)
                        Text(if (descending) "涨幅从高到低" else "涨幅从低到高", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = { descending = !descending }) { Text(if (descending) "↓ 涨幅" else "↑ 涨幅") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = autoRefresh, onClick = { autoRefresh = !autoRefresh },
                        label = { Text(if (autoRefresh) "行情刷新 · 30秒" else "开启自动刷新") }, colors = assetChipColors())
                    FilterChip(selected = autoPlay, onClick = { autoPlay = !autoPlay },
                        label = { Text(if (autoPlay) "暂停轮播" else "自动轮播") }, colors = assetChipColors())
                }
            }
        }
        OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("搜索半导体、游戏、人工智能…") }, singleLine = true, shape = RoundedCornerShape(15.dp), colors = fieldColors())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("全部", "芯片硬件", "数字内容", "AI 算力", "通信网络").forEach { item ->
                FilterChip(selected = category == item, onClick = { category = item }, label = { Text(item) }, colors = assetChipColors())
            }
        }
        if (message.isNotBlank()) Text(message, color = Muted, fontSize = 11.sp)
        if (sectors.isEmpty()) {
            EmptyCard("没有匹配板块", "换个名称关键词，或清除筛选条件。")
        } else {
            SectorRollingCards(sectors, quotes, bars, autoPlay, onOpenMarket)
        }
        Text("板块使用对应行业 ETF 作为可交易代理，价格与涨幅不等同于完整行业指数；报价可能延迟。", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun SectorRiskCard(result: RiskAnalysisResult) {
    Card(Modifier.fillMaxWidth(), shape = TacticalPanel, colors = CardDefaults.cardColors(containerColor = White),
        border = BorderStroke(1.dp, Color(0xFFD8D8D2))) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("板块风险体检", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            when (result) {
                is RiskAnalysisResult.InsufficientHistory -> Text(
                    "共同日线 ${result.available} 天；至少需要 ${result.required} 天后计算波动与尾部风险。",
                    color = Muted, fontSize = 11.sp, lineHeight = 16.sp
                )
                is RiskAnalysisResult.Ready -> {
                    val risk = result.summary
                    Text("${risk.observationCount} 个共同交易日 · 等权参考组合 · 历史统计，不是预测",
                        color = Muted, fontSize = 10.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        SmallStat("年化波动", "${String.format("%.1f", risk.annualizedVolatilityPct)}%", Modifier.weight(1f))
                        SmallStat("历史 VaR 95%", "${String.format("%.2f", risk.historicalVar95Pct)}%/日", Modifier.weight(1f))
                        SmallStat("CVaR 95%", "${String.format("%.2f", risk.historicalCvar95Pct)}%/日", Modifier.weight(1f))
                    }
                    risk.highestCorrelation?.let { pair ->
                        Text("最高相关：${pair.first} × ${pair.second}  ${String.format("%.2f", pair.correlation)}",
                            color = Muted, fontSize = 10.sp)
                    }
                    Text("收缩强度 ${String.format("%.0f", risk.covarianceShrinkage * 100)}% · EWMA 波动 ${String.format("%.1f", risk.ewmaVolatilityPct)}% 年化",
                        color = Muted, fontSize = 10.sp)
                    risk.contributions.forEach { contribution ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(contribution.name, Modifier.weight(1f), color = Ink, fontSize = 10.sp)
                            Text("风险贡献 ${String.format("%.1f", contribution.percent)}%", color = Muted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectorRollingCards(
    sectors: List<MarketSector>, quotes: Map<String, MarketQuote>, bars: Map<String, List<MarketOhlcBar>>,
    enabled: Boolean, onOpenMarket: (String) -> Unit
) {
    val density = LocalDensity.current
    val offset = remember { Animatable(0f) }
    var cycleHeightPx by remember { mutableStateOf(0) }
    val ids = sectors.joinToString("|") { it.instrumentId }
    LaunchedEffect(enabled, ids, cycleHeightPx) {
        if (!enabled || cycleHeightPx <= 0) {
            offset.snapTo(0f)
            return@LaunchedEffect
        }
        val pixelsPerSecond = with(density) { 8.dp.toPx() }
        val duration = ((cycleHeightPx / pixelsPerSecond) * 1_000).roundToInt().coerceAtLeast(1_000)
        while (isActive) {
            offset.animateTo(cycleHeightPx.toFloat(), tween(duration, easing = LinearEasing))
            offset.snapTo(0f)
        }
    }
    Box(
        Modifier.fillMaxWidth().height(330.dp).clip(RoundedCornerShape(18.dp))
    ) {
        SectorCardColumn(sectors, quotes, bars, onOpenMarket,
            Modifier.fillMaxWidth().graphicsLayer { translationY = -offset.value }.onSizeChanged { cycleHeightPx = it.height })
        if (enabled && cycleHeightPx > 0) {
            SectorCardColumn(sectors, quotes, bars, onOpenMarket,
                Modifier.fillMaxWidth().graphicsLayer { translationY = cycleHeightPx - offset.value })
        }
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(12.dp)
            .background(Brush.verticalGradient(listOf(White.copy(alpha = .72f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(12.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, White.copy(alpha = .72f)))))
    }
}

@Composable
private fun SectorCardColumn(
    sectors: List<MarketSector>, quotes: Map<String, MarketQuote>, bars: Map<String, List<MarketOhlcBar>>,
    onOpenMarket: (String) -> Unit, modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(13.dp)) {
        sectors.forEachIndexed { index, sector ->
            val id = sector.instrumentId
            val instrument = MarketInstrumentCatalog.byId[id] ?: return@forEachIndexed
            val quote = quotes[id]
            Card(onClick = { onOpenMarket(id) }, modifier = Modifier.fillMaxWidth().animateContentSize(tween(220)),
                shape = RoundedCornerShape(17.dp),
                colors = CardDefaults.cardColors(containerColor = White), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 6.dp),
                border = BorderStroke(1.dp, Color(0xFFD8D8D2))) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text((index + 1).toString().padStart(2, '0'), color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(sector.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(sector.category, color = Blue, fontSize = 10.sp)
                        }
                        Text(instrument.name + " · 点开看走势与指标", color = Muted, fontSize = 10.sp)
                    }
                    SectorSparkline(bars[id].orEmpty(), Modifier.width(62.dp).height(32.dp), quote?.changePct?.let { it >= 0 } ?: true)
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        AnimatedTicker(quote?.let { String.format("%.3f", it.price) } ?: "—", Ink, 13.sp)
                        AnimatedTicker(quote?.let { it.changePct.asSignedPercent() } ?: "暂无报价",
                            quote?.let { plColor(BigDecimal.valueOf(it.changePct)) } ?: Muted, 11.sp, FontWeight.Medium)
                    }
                }
            }
        }
        Spacer(Modifier.height(13.dp))
    }
}

@Composable
private fun SectorSparkline(bars: List<MarketOhlcBar>, modifier: Modifier = Modifier, positive: Boolean) {
    if (bars.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("—", color = Muted, fontSize = 13.sp) }
        return
    }
    val color = if (positive) MarketUp else MarketDown
    Canvas(modifier) {
        val values = bars.takeLast(30).map { it.close }
        val min = values.minOrNull() ?: return@Canvas
        val range = ((values.maxOrNull() ?: min) - min).coerceAtLeast(0.000001)
        val path = Path()
        values.forEachIndexed { i, value ->
            val x = size.width * i / (values.lastIndex).coerceAtLeast(1)
            val y = size.height - ((value - min) / range).toFloat() * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun MarketDetailScreen(
    secid: String, quote: MarketQuote?, marketRepository: MarketRepository, onBack: () -> Unit,
    onToggle: (Boolean) -> Unit, isWatched: Boolean, modifier: Modifier = Modifier
) {
    val instrument = MarketInstrumentCatalog.byId[secid]
    var period by remember(secid) { mutableStateOf(ChartPeriod.DAY) }
    var autoLive by remember(secid) { mutableStateOf(true) }
    var displayedQuote by remember(secid) { mutableStateOf(quote) }
    var style by remember(secid) { mutableStateOf(ChartStyle.CANDLE) }
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
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("‹ 自选", color = Blue) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onToggle(!isWatched) }) { Text(if (isWatched) "★ 已自选" else "☆ 加自选", color = Blue) }
            DrawerMenuButton()
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(instrument?.name ?: "行情详情", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            Surface(shape = CircleShape, color = SoftBlue) {
                Text(instrument?.market ?: "市场", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = Blue, fontSize = 10.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(displayedQuote?.let { if (it.source == "daily-close") "最近收盘 ${it.displayTime()}" else "行情更新 ${it.displayTime()}" } ?: "行情暂无报价", Modifier.weight(1f), color = Muted, fontSize = 11.sp)
            if (period == ChartPeriod.INTRADAY) {
                FilterChip(selected = autoLive, onClick = { autoLive = !autoLive },
                    label = { Text(if (autoLive) "实时 · 15秒刷新" else "实时 · 已暂停", fontSize = 10.sp) }, colors = assetChipColors())
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            androidx.compose.animation.AnimatedContent(targetState = displayedQuote?.price ?: bars.lastOrNull()?.close,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "price_change") { value ->
                Text(value?.let { String.format("%.2f", it) } ?: "—", color = Ink, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            val pct = displayedQuote?.changePct ?: selectedPct
            if (pct != null) Text("${if (pct > 0) "+" else ""}${String.format("%.2f", pct)}%", color = plColor(BigDecimal.valueOf(pct)), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Card(Modifier.fillMaxWidth(), shape = CutCornerShape(4.dp), colors = CardDefaults.cardColors(containerColor = White),
            border = BorderStroke(1.dp, Color(0xFFD8D8D2))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("开盘" to summaryOpen, "最高" to summaryHigh, "最低" to summaryLow, "收盘" to summaryClose).forEach { (label, value) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(label, color = Muted, fontSize = 10.sp)
                        Text(value?.let { String.format("%.2f", it) } ?: "—", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth(), shape = TacticalPanel, colors = CardDefaults.cardColors(containerColor = White),
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
                        Text(if (period == ChartPeriod.INTRADAY || period == ChartPeriod.FIVE_DAY) "分时走势" else "${period.label}走势", color = Ink, fontWeight = FontWeight.SemiBold)
                        Text(if (visibleBars.isEmpty()) "等待历史数据" else "${chartDate(visibleBars.first().day)} — ${chartDate(visibleBars.last().day)}  ·  ${if (period == ChartPeriod.INTRADAY || period == ChartPeriod.FIVE_DAY) "30分钟" else "${visibleBars.size} 根"}", color = Muted, fontSize = 10.sp)
                    }
                    TextButton(onClick = { load(force = true) }, enabled = !loading) { Text(if (loading) "加载中" else "更新") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    FilterChip(selected = style == ChartStyle.LINE, onClick = { style = ChartStyle.LINE }, label = { Text("走势") }, colors = assetChipColors())
                    FilterChip(selected = style == ChartStyle.CANDLE, onClick = { style = ChartStyle.CANDLE }, label = { Text("K线") }, colors = assetChipColors())
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
                        Text(if (loading) "正在加载行情…" else "当前市场暂无此周期数据", color = Muted, fontSize = 13.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(visibleBars.firstOrNull()?.let { chartDate(it.day) } ?: "—", color = Muted, fontSize = 9.sp)
                    Text(selectedBar?.let { "已选 ${chartDate(it.day)}" } ?: "拖动图表查看", color = Blue, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                    Text(visibleBars.lastOrNull()?.let { chartDate(it.day) } ?: "—", color = Muted, fontSize = 9.sp)
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
                            Text("${chartDate(selected.day)}  ·  涨跌 ${selectedPct?.let { "${if (it > 0) "+" else ""}${String.format("%.2f", it)}%" } ?: "—"}", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                listOf("开" to selected.open, "高" to selected.high, "低" to selected.low, "收" to selected.close).forEach { (label, value) ->
                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(label, color = Muted, fontSize = 9.sp)
                                        Text(String.format("%.2f", value), color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("成交量 ${formatVolume(selected.volume)}", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                if (showMa5) Text("MA5 ${ma5?.let { String.format("%.2f", it) } ?: "—"}", color = Gold, fontSize = 10.sp)
                                if (showMa20) Text("MA20 ${ma20?.let { String.format("%.2f", it) } ?: "—"}", color = Color(0xFF8B78C8), fontSize = 10.sp)
                            }
                        }
                    } else Text("触摸图表可查看单根行情", color = Muted, fontSize = 11.sp)
                }
                Text("拖动图表查看单根数据；点选 MA5 / MA20 与成交量切换指标。", color = Muted, fontSize = 10.sp)
            }
        }
        if (message.isNotBlank()) Text(message, color = Muted, fontSize = 11.sp)
        Text("公开市场 OHLC 数据可能延迟；缺失周期不会用其他周期数据补画。不能替代交易终端报价。", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun MarketChart(
    visibleBars: List<MarketOhlcBar>, style: ChartStyle, showMa5: Boolean, showMa20: Boolean, showVolume: Boolean,
    selectedIndex: Int, modifier: Modifier = Modifier, onIndexSelected: (Int) -> Unit
) {
    if (visibleBars.isEmpty()) return
    val chartReveal = remember(visibleBars.size, visibleBars.first().day, visibleBars.last().day) { Animatable(0f) }
    LaunchedEffect(chartReveal) { chartReveal.animateTo(1f, tween(360)) }
    val selected = selectedIndex.coerceIn(0, visibleBars.lastIndex)
    val touchMod = modifier.graphicsLayer {
        alpha = chartReveal.value
        scaleY = .92f + .08f * chartReveal.value
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
    }.pointerInput(visibleBars.size) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            fun select(x: Float) {
                val padding = 6.dp.toPx()
                val usable = (size.width - padding * 2).coerceAtLeast(1f)
                val fraction = ((x - padding) / usable).coerceIn(0f, 1f)
                onIndexSelected((fraction * (visibleBars.size - 1)).roundToInt())
            }
            select(down.position.x)
            do {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.pressed) { select(change.position.x); change.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
    Canvas(touchMod) {
        val left = 6.dp.toPx(); val right = size.width - 6.dp.toPx()
        val volumeHeight = if (showVolume) 30.dp.toPx() else 0f
        val top = 10.dp.toPx(); val bottom = size.height - 8.dp.toPx() - volumeHeight
        val min = visibleBars.minOf { it.low }
        val max = visibleBars.maxOf { it.high }
        val range = (max - min).coerceAtLeast(0.000001)
        repeat(4) { row ->
            val y = top + (bottom - top) * row / 3f
                drawLine(Color(0xFFE4E2DC), Offset(left, y), Offset(right, y), 1.dp.toPx())
        }
        fun y(value: Double) = bottom - ((value - min) / range).toFloat() * (bottom - top)
        if (style == ChartStyle.LINE) {
            val path = Path()
            visibleBars.forEachIndexed { index, bar ->
                val x = left + (right - left) * index / (visibleBars.size - 1).coerceAtLeast(1)
                val pointY = y(bar.close)
                if (index == 0) path.moveTo(x, pointY) else path.lineTo(x, pointY)
            }
            if (visibleBars.size > 1) {
                val fill = Path().apply {
                    addPath(path)
                    lineTo(right, bottom); lineTo(left, bottom); close()
                }
                drawPath(fill, SoftBlue.copy(alpha = .52f), style = Fill)
            }
            drawPath(path, Blue, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
            if (visibleBars.size == 1) drawCircle(Blue, 4.dp.toPx(), Offset(left, y(visibleBars.first().close)))
            fun movingAverage(period: Int, color: Color) {
                if (visibleBars.size < period) return
                val average = Path()
                for (i in period - 1 until visibleBars.size) {
                    val mean = visibleBars.subList(i - period + 1, i + 1).map { it.close }.average()
                    val x = left + (right - left) * i / (visibleBars.size - 1).coerceAtLeast(1)
                    if (i == period - 1) average.moveTo(x, y(mean)) else average.lineTo(x, y(mean))
                }
                drawPath(average, color, style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round))
            }
            if (showMa5) movingAverage(5, Gold)
            if (showMa20) movingAverage(20, Color(0xFF8B78C8))
        } else {
            val slot = (right - left) / visibleBars.size
            val bodyWidth = (slot * .58f).coerceAtMost(8.dp.toPx()).coerceAtLeast(2.dp.toPx())
            visibleBars.forEachIndexed { index, bar ->
                val x = left + slot * (index + .5f)
                val color = if (bar.close >= bar.open) MarketUp else MarketDown
                drawLine(color, Offset(x, y(bar.high)), Offset(x, y(bar.low)), 1.2.dp.toPx())
                val topBody = y(maxOf(bar.open, bar.close))
                val bottomBody = y(minOf(bar.open, bar.close))
                drawRect(color, topLeft = Offset(x - bodyWidth / 2, topBody), size = androidx.compose.ui.geometry.Size(bodyWidth, (bottomBody - topBody).coerceAtLeast(1.5.dp.toPx())))
            }
            fun movingAverage(period: Int, color: Color) {
                if (visibleBars.size < period) return
                val average = Path()
                for (i in period - 1 until visibleBars.size) {
                    val mean = visibleBars.subList(i - period + 1, i + 1).map { it.close }.average()
                    val x = left + slot * (i + .5f)
                    if (i == period - 1) average.moveTo(x, y(mean)) else average.lineTo(x, y(mean))
                }
                drawPath(average, color, style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round))
            }
            if (showMa5) movingAverage(5, Gold)
            if (showMa20) movingAverage(20, Color(0xFF8B78C8))
        }
        if (showVolume) {
            val maxVolume = visibleBars.maxOfOrNull { it.volume }?.coerceAtLeast(1.0) ?: 1.0
            val volTop = size.height - volumeHeight
            val volBottom = size.height - 3.dp.toPx()
            val slot = (right - left) / visibleBars.size
            visibleBars.forEachIndexed { index, bar ->
                val height = ((bar.volume / maxVolume).toFloat() * (volBottom - volTop)).coerceAtLeast(1.dp.toPx())
                val x = left + slot * (index + .5f)
                val color = if (bar.close >= bar.open) MarketUp else MarketDown
                drawRect(color.copy(alpha = .5f), Offset(x - (slot * .55f) / 2, volBottom - height), Size((slot * .55f).coerceAtLeast(1.dp.toPx()), height))
            }
        }
        if (visibleBars.isNotEmpty()) {
            val slot = (right - left) / visibleBars.size
            val x = if (style == ChartStyle.LINE) left + (right - left) * selected / (visibleBars.size - 1).coerceAtLeast(1) else left + slot * (selected + .5f)
            drawLine(Color(0xFF9AA7B8), Offset(x, top), Offset(x, if (showVolume) size.height - volumeHeight else bottom), 1.dp.toPx())
            val selectedBar = visibleBars[selected]
            val pointY = if (style == ChartStyle.LINE) y(selectedBar.close) else y((selectedBar.open + selectedBar.close) / 2.0)
            drawCircle(White, 5.dp.toPx(), Offset(x, pointY))
            drawCircle(Blue, 3.dp.toPx(), Offset(x, pointY))
        }
    }
}

@Composable
private fun IndicatorPane(bars: List<MarketOhlcBar>, selectedIndex: Int, macd: Boolean) {
    if (bars.isEmpty()) return
    val index = selectedIndex.coerceIn(0, bars.lastIndex)
    if (!macd) {
        val values = MarketIndicators.rsi(bars)
        val selected = values.getOrNull(index)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("RSI(14)", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(selected?.let { String.format("%.1f", it) } ?: "样本不足", color = Blue, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text("参考 30 / 70", color = Muted, fontSize = 9.sp)
        }
        Canvas(Modifier.fillMaxWidth().height(62.dp)) {
            val left = 6.dp.toPx(); val right = size.width - 6.dp.toPx()
            fun y(value: Double) = size.height * (1.0 - value / 100.0).toFloat()
            listOf(30.0, 50.0, 70.0).forEach { value ->
                drawLine(Color(0xFFE4E2DC), Offset(left, y(value)), Offset(right, y(value)), 1.dp.toPx())
            }
            val path = Path(); var started = false
            values.forEachIndexed { i, value ->
                if (value == null) return@forEachIndexed
                val x = left + (right - left) * i / (bars.size - 1).coerceAtLeast(1)
                if (!started) { path.moveTo(x, y(value)); started = true } else path.lineTo(x, y(value))
            }
            drawPath(path, Color(0xFF8B78C8), style = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round))
            val cursorX = left + (right - left) * index / (bars.size - 1).coerceAtLeast(1)
            drawLine(Color(0xFF9AA7B8), Offset(cursorX, 0f), Offset(cursorX, size.height), 1.dp.toPx())
        }
    } else {
        val values = MarketIndicators.macd(bars)
        val selected = values.getOrNull(index)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("MACD(12,26,9)", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(selected?.let { "DIF ${String.format("%.3f", it.dif)} · DEA ${String.format("%.3f", it.dea)}" } ?: "—",
                color = Blue, fontSize = 9.sp)
            Spacer(Modifier.weight(1f))
            Text("柱 ${selected?.let { String.format("%.3f", it.histogram) } ?: "—"}", color = Muted, fontSize = 9.sp)
        }
        Canvas(Modifier.fillMaxWidth().height(62.dp)) {
            val left = 6.dp.toPx(); val right = size.width - 6.dp.toPx(); val center = size.height / 2
            val maxAbs = values.maxOfOrNull { maxOf(kotlin.math.abs(it.dif), kotlin.math.abs(it.dea), kotlin.math.abs(it.histogram)) }
                ?.coerceAtLeast(0.000001) ?: 1.0
            drawLine(Color(0xFFE4E2DC), Offset(left, center), Offset(right, center), 1.dp.toPx())
            val slot = (right - left) / values.size
            values.forEachIndexed { i, value ->
                val height = (kotlin.math.abs(value.histogram) / maxAbs).toFloat() * (size.height * .43f)
                val x = left + slot * (i + .5f)
                drawRect(if (value.histogram >= 0) MarketUp.copy(alpha = .7f) else MarketDown.copy(alpha = .7f),
                    Offset(x - (slot * .55f).coerceAtLeast(1.dp.toPx()) / 2, if (value.histogram >= 0) center - height else center),
                    Size((slot * .55f).coerceAtLeast(1.dp.toPx()), height.coerceAtLeast(1.dp.toPx())))
            }
            fun lineFor(value: (MacdValue) -> Double, color: Color) {
                val path = Path()
                values.forEachIndexed { i, item ->
                    val x = left + (right - left) * i / (values.size - 1).coerceAtLeast(1)
                    val y = center - (value(item) / maxAbs).toFloat() * (size.height * .43f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 1.25.dp.toPx(), cap = StrokeCap.Round))
            }
            lineFor({ it.dif }, Blue); lineFor({ it.dea }, Gold)
            val cursorX = left + (right - left) * index / (values.size - 1).coerceAtLeast(1)
            drawLine(Color(0xFF9AA7B8), Offset(cursorX, 0f), Offset(cursorX, size.height), 1.dp.toPx())
        }
    }
}

private fun filteredChartBars(bars: List<MarketOhlcBar>, period: ChartPeriod): List<MarketOhlcBar> = when (period) {
    ChartPeriod.INTRADAY -> {
        val lastDay = bars.lastOrNull()?.day?.take(8)
        bars.filter { it.day.take(8) == lastDay }
    }
    ChartPeriod.FIVE_DAY -> {
        val days = bars.map { it.day.take(8) }.distinct().takeLast(5).toSet()
        bars.filter { it.day.take(8) in days }
    }
    else -> bars
}

private fun chartDate(value: String): String = when {
    value.length >= 12 && value.all(Char::isDigit) -> "${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)} ${value.substring(8, 10)}:${value.substring(10, 12)}"
    value.length == 8 && value.all(Char::isDigit) -> "${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)}"
    else -> value
}

private fun formatVolume(value: Double): String = when {
    value >= 100_000_000 -> String.format("%.2f亿", value / 100_000_000)
    value >= 10_000 -> String.format("%.1f万", value / 10_000)
    value > 0 -> String.format("%.0f", value)
    else -> "—"
}

@Composable
private fun SmallStat(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = CutCornerShape(3.dp), colors = CardDefaults.cardColors(containerColor = White)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Muted, fontSize = 12.sp)
            Text(value, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EmptyCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth(), shape = CutCornerShape(3.dp), colors = CardDefaults.cardColors(containerColor = White)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
            Text(body, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String, color: Color) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = SoftBlue)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = color, fontWeight = FontWeight.SemiBold)
            Text(body, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PositionCard(p: Position) {
    Card(Modifier.fillMaxWidth(), shape = CutCornerShape(topStart = 2.dp, topEnd = 15.dp, bottomEnd = 2.dp, bottomStart = 15.dp), colors = CardDefaults.cardColors(containerColor = White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.asset.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(p.asset.symbol, color = Muted, fontSize = 11.sp)
                }
                Surface(shape = CutCornerShape(3.dp), color = if (p.asset.kind == AssetKind.GOLD) Color(0xFFF0E6D3) else Color(0xFFE7EBE8)) {
                    Text(if (p.asset.kind == AssetKind.GOLD) "黄金" else "基金", Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = if (p.asset.kind == AssetKind.GOLD) Blue else Muted, fontSize = 10.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (p.quote == null) "待更新价格" else "市值 ¥${p.value!!.money()}", Modifier.weight(1f), color = Ink, fontSize = 13.sp)
                Text(if (p.quote == null) "成本 ¥${p.cost.money()}" else "盈亏 ${if (p.holdingProfit!!.signum() > 0) "+" else ""}¥${p.holdingProfit!!.money()}", color = plColor(p.holdingProfit ?: BigDecimal.ZERO), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Text("持有 ${p.units.qty()} ${if (p.asset.kind == AssetKind.GOLD) "克" else "份"}  ·  成本 ¥${p.cost.money()}", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun RecordScreen(
    ledger: Ledger,
    pending: List<Trade>,
    cashBalance: AccountCashBalance?,
    accountCashEvents: List<AccountCashEvent>,
    snackbarHostState: SnackbarHostState,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(AssetKind.FUND) }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<AssetInfo?>(null) }
    var side by remember { mutableStateOf(TradeSide.BUY) }
    var amount by remember { mutableStateOf("") }
    var units by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("0") }
    var showFee by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var messageIsError by remember { mutableStateOf(false) }
    var customCode by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    var confirmTarget by remember { mutableStateOf<Trade?>(null) }
    var confirmPrice by remember { mutableStateOf("") }
    var cashAmount by remember { mutableStateOf("") }
    var cashMemo by remember { mutableStateOf("") }
    var cashEventType by remember { mutableStateOf(AccountCashEventType.DEPOSIT) }
    val recentCashEvents = accountCashEvents.filter {
        it.type == AccountCashEventType.DEPOSIT || it.type == AccountCashEventType.WITHDRAWAL
    }.takeLast(4).reversed()
    val candidates = remember(filter, search) {
        if (search.isBlank()) emptyList() else ledger.assets(filter)
            .filter { it.name.contains(search.trim(), true) || it.symbol.contains(search.trim(), true) }.take(10)
    }
    val quickPicks = remember(filter) {
        val assets = ledger.assets(filter)
        if (filter == AssetKind.FUND) {
            val preferred = listOf("005827", "003095", "110022", "000961")
            preferred.mapNotNull { code -> assets.firstOrNull { it.symbol == code } }.ifEmpty { assets.take(4) }
        } else assets.take(3)
    }

    ScreenColumn(modifier, spacing = 11.dp, verticalPadding = 10.dp) {
        BrandHeader("记一笔")
        Text(
            when (side) {
                TradeSide.BUY -> "记录新买入；确认净值可以稍后补录。"
                TradeSide.SELL -> "记录卖出或赎回，不能超过当前持有份额。"
                TradeSide.OPENING_POSITION -> "录入已有持仓的份额和历史成本，从今天开始建立快照。"
            },
            color = Muted, fontSize = 13.sp, lineHeight = 20.sp
        )

        Card(Modifier.fillMaxWidth(), shape = TacticalPanel, colors = CardDefaults.cardColors(containerColor = White),
            border = BorderStroke(1.dp, Color(0xFFD8D8D2))) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("账户现金", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (cashBalance == null) "启用后记录入金、出金和买卖对现金的影响" else "现金跟踪起点 ${cashBalance.trackingStart}", color = Muted, fontSize = 10.sp)
                    }
                    Text(cashBalance?.amount?.let { "¥${it.money()}" } ?: "未启用", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                if (cashBalance == null) {
                    NumericField(cashAmount, { cashAmount = it }, "从今天开始的期初可用现金（元）", Modifier.fillMaxWidth())
                    Text("既有基金和黄金持仓继续保留；历史买卖不会改写。从起点后确认的新买卖会自动记入现金。", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                    Button(onClick = {
                        runCatching { ledger.initializeAccountCash(cashAmount.toBigDecimalOrNull() ?: error("请填写期初现金")) }
                            .onSuccess { cashAmount = ""; message = "现金跟踪已启用"; messageIsError = false; onChanged() }
                            .onFailure { message = it.message ?: "设置失败"; messageIsError = true }
                    }, enabled = cashAmount.isNotBlank(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text("开始跟踪现金") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        FilterChip(selected = cashEventType == AccountCashEventType.DEPOSIT,
                            onClick = { cashEventType = AccountCashEventType.DEPOSIT }, label = { Text("入金") }, colors = assetChipColors())
                        FilterChip(selected = cashEventType == AccountCashEventType.WITHDRAWAL,
                            onClick = { cashEventType = AccountCashEventType.WITHDRAWAL }, label = { Text("出金") }, colors = assetChipColors())
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        NumericField(cashAmount, { cashAmount = it }, "金额（元）", Modifier.weight(1f))
                        Button(onClick = {
                            runCatching { ledger.addExternalCashMovement(cashEventType, cashAmount.toBigDecimalOrNull() ?: error("请填写金额"), cashMemo) }
                                .onSuccess { cashAmount = ""; cashMemo = ""; message = if (cashEventType == AccountCashEventType.DEPOSIT) "入金已记录" else "出金已记录"; messageIsError = false; onChanged() }
                                .onFailure { message = it.message ?: "保存失败"; messageIsError = true }
                        }, enabled = cashAmount.isNotBlank(), shape = RoundedCornerShape(13.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text(if (cashEventType == AccountCashEventType.DEPOSIT) "记入金" else "记出金") }
                    }
                    OutlinedTextField(cashMemo, { cashMemo = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("备注（选填）") }, singleLine = true, shape = RoundedCornerShape(13.dp), colors = fieldColors())
                    recentCashEvents.forEach { event ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${event.day} · ${if (event.type == AccountCashEventType.DEPOSIT) "入金" else "出金"}${event.memo.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}", color = Muted, fontSize = 10.sp, maxLines = 1)
                            Text("${if (event.type == AccountCashEventType.DEPOSIT) "+" else "−"}¥${event.amount.money()}", color = Ink, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter == AssetKind.FUND, onClick = { filter = AssetKind.FUND; selected = null; search = "" }, label = { Text("基金") }, colors = assetChipColors())
            FilterChip(selected = filter == AssetKind.GOLD, onClick = { filter = AssetKind.GOLD; selected = null; search = "" }, label = { Text("黄金") }, colors = assetChipColors())
        }
        if (selected == null) {
            OutlinedTextField(
                search, { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(if (filter == AssetKind.FUND) "搜索基金名称" else "搜索黄金品种") },
                placeholder = { Text(if (filter == AssetKind.FUND) "例如：易方达蓝筹、沪深300" else "例如：积存金、Au99.99") },
                leadingIcon = { Text("⌕", color = Muted, fontSize = 23.sp) },
                trailingIcon = if (search.isNotEmpty()) ({ TextButton(onClick = { search = "" }) { Text("清除") } }) else null,
                shape = RoundedCornerShape(18.dp), colors = fieldColors()
            )
            if (candidates.isNotEmpty()) {
                Text("搜索结果 · ${candidates.size}", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Card(Modifier.fillMaxWidth().heightIn(max = 370.dp).verticalScroll(rememberScrollState()), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = White)) {
                    Column {
                        candidates.forEach { asset ->
                            TextButton(onClick = { selected = asset; search = "" }, modifier = Modifier.fillMaxWidth().height(66.dp)) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(asset.name, color = Ink, fontWeight = FontWeight.Medium)
                                    Text("${asset.category}  ·  ${asset.symbol}", color = Muted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            } else if (search.isNotBlank()) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = White)) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("没有找到“$search”", color = Ink, fontWeight = FontWeight.SemiBold)
                        Text("你可以手动补充名称和代码。", color = Muted, fontSize = 12.sp)
                        OutlinedTextField(customName, { customName = it }, label = { Text("基金 / 黄金名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = fieldColors())
                        OutlinedTextField(customCode, { customCode = it }, label = { Text("代码（选填）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = fieldColors())
                        Button(onClick = {
                            val displayName = customName.ifBlank { search.trim() }
                            val identifier = customCode.ifBlank { "LOCAL_${displayName.hashCode().toUInt().toString(16)}" }
                            runCatching { ledger.addCustomAsset(identifier, displayName, filter) }
                                .onSuccess { selected = it; search = ""; customName = ""; customCode = "" }
                                .onFailure { message = it.message ?: "添加失败"; messageIsError = true }
                        }) { Text("使用这个标的") }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (filter == AssetKind.FUND) "常用基金" else "常用黄金", Modifier.weight(1f), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text("名称可搜简称", color = Muted, fontSize = 10.sp)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    quickPicks.forEach { asset ->
                        FilterChip(selected = false, onClick = { selected = asset }, label = { Text(asset.name.take(8)) }, colors = assetChipColors())
                    }
                }
            }
        } else {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = SoftBlue)) {
                Row(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(selected!!.name, color = Ink, fontWeight = FontWeight.SemiBold)
                        Text("${selected!!.category}  ·  ${selected!!.symbol}", color = Muted, fontSize = 11.sp)
                    }
                    TextButton(onClick = { selected = null }) { Text("更换") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = side == TradeSide.BUY, onClick = {
                    side = TradeSide.BUY; amount = ""; units = ""; price = ""
                }, label = { Text("买入") }, colors = assetChipColors())
                FilterChip(selected = side == TradeSide.SELL, onClick = {
                    side = TradeSide.SELL; amount = ""; units = ""; price = ""
                }, label = { Text("卖出 / 赎回") }, colors = assetChipColors())
                FilterChip(selected = side == TradeSide.OPENING_POSITION, onClick = {
                    side = TradeSide.OPENING_POSITION; amount = ""; units = ""; price = ""
                }, label = { Text("期初持仓") }, colors = assetChipColors())
            }
            if (side == TradeSide.OPENING_POSITION) {
                NumericField(units, { units = it }, if (selected!!.kind == AssetKind.GOLD) "当前持有克数 · 必填" else "当前持有份额 · 必填", Modifier.fillMaxWidth())
                NumericField(amount, { amount = it }, "历史持仓成本（元）· 必填", Modifier.fillMaxWidth())
                InfoCard("从今天建立记录", "不补造过去的买入流水或现金变化；较早的收益不会计入今天的周期盈亏。", Ink)
            } else if (side == TradeSide.BUY) {
                NumericField(amount, { amount = it }, "买入金额（元）· 必填", Modifier.fillMaxWidth())
            } else {
                NumericField(units, { units = it }, if (selected!!.kind == AssetKind.GOLD) "卖出克数" else "赎回份额", Modifier.fillMaxWidth())
            }
            if (side != TradeSide.OPENING_POSITION) {
                NumericField(price, { price = it }, if (selected!!.kind == AssetKind.GOLD) "成交克价（可稍后补）" else "确认净值（可稍后补）", Modifier.fillMaxWidth())
            }
            if (side == TradeSide.BUY) {
                TextButton(onClick = { showFee = !showFee }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                    Text(if (showFee) "− 收起手续费" else "+ 填写手续费（选填）", color = Muted)
                }
                if (showFee) NumericField(fee, { fee = it }, "手续费（元）", Modifier.fillMaxWidth())
                InfoCard("净值还没出来？", "先记金额，净值公布后补录，份额会自动算。", Ink)
            }
            Button(
                onClick = {
                    try {
                        val savedTradeId: Long
                        val savedMessage: String
                        if (side == TradeSide.OPENING_POSITION) {
                            val openingUnits = units.toBigDecimalOrNull() ?: error("请填写当前持有份额")
                            val openingCost = amount.toBigDecimalOrNull() ?: error("请填写历史持仓成本")
                            savedTradeId = ledger.addOpeningPosition(requireNotNull(selected), openingUnits, openingCost)
                            savedMessage = "期初持仓已录入"
                        } else {
                            val tradePrice = price.toBigDecimalOrNull()
                            val tradeFee = fee.toBigDecimalOrNull() ?: BigDecimal.ZERO
                            val tradeAmount: BigDecimal
                            val tradeUnits: BigDecimal?
                            if (side == TradeSide.BUY) {
                                tradeAmount = amount.toBigDecimalOrNull() ?: error("先填写买入金额")
                                tradeUnits = tradePrice?.let { tradeAmount.subtract(tradeFee).divide(it, 8, RoundingMode.DOWN) }
                            } else {
                                tradeUnits = units.toBigDecimalOrNull() ?: error("填写赎回份额")
                                tradeAmount = tradePrice?.multiply(tradeUnits) ?: BigDecimal.ZERO
                            }
                            savedTradeId = ledger.addTrade(requireNotNull(selected), side, tradeAmount, tradeUnits, tradePrice, tradeFee)
                            savedMessage = if (tradePrice == null) "已记下，净值确认后请补录价格" else "流水已保存"
                        }
                        message = savedMessage
                        messageIsError = false
                        amount = ""; units = ""; price = ""; fee = "0"; showFee = false
                        onChanged()
                        scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            val snackbar = async {
                                snackbarHostState.showSnackbar(
                                    message = savedMessage,
                                    actionLabel = "撤销",
                                    withDismissAction = true,
                                    duration = SnackbarDuration.Indefinite
                                )
                            }
                            val result = withTimeoutOrNull(5_000L) { snackbar.await() }
                            if (result == SnackbarResult.ActionPerformed) {
                                runCatching { ledger.deleteTrade(savedTradeId) }
                                    .onSuccess {
                                        message = "已撤销刚才的流水"
                                        messageIsError = false
                                        onChanged()
                                    }
                                    .onFailure {
                                        message = it.message ?: "无法撤销这笔流水"
                                        messageIsError = true
                                    }
                            } else if (result == null) {
                                snackbarHostState.currentSnackbarData?.dismiss()
                            }
                        }
                    } catch (e: Exception) { message = e.message ?: "请检查填写内容"; messageIsError = true }
                }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink)
            ) {
                Text(when (side) {
                    TradeSide.BUY -> "记下买入${amount.takeIf { it.isNotBlank() }?.let { " ¥$it" } ?: ""}"
                    TradeSide.SELL -> "记下赎回"
                    TradeSide.OPENING_POSITION -> "录入期初持仓"
                }, fontWeight = FontWeight.SemiBold)
            }
        }
        if (message.isNotBlank()) Text(message, color = if (messageIsError) ErrorColor else Ink, fontSize = 13.sp)

        if (pending.isNotEmpty()) {
            Text("待确认交易", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            pending.forEach { trade ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = White)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(trade.asset.name, color = Ink, fontWeight = FontWeight.Medium)
                            Text(if (trade.side == TradeSide.BUY) "申购 ¥${trade.amount.money()} · ${trade.day}" else "赎回 ${trade.units?.qty()} 份 · ${trade.day}", color = Muted, fontSize = 11.sp)
                        }
                        TextButton(onClick = { confirmTarget = trade; confirmPrice = "" }) { Text("补录净值") }
                    }
                }
            }
        }
        if (selected != null) Text(
            if (side == TradeSide.OPENING_POSITION) "录入日期：今天 · 历史收益不追溯" else "交易时间：今天 · 可稍后补充确认净值",
            color = Muted, fontSize = 11.sp
        )
    }

    confirmTarget?.let { trade ->
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text("确认 ${trade.asset.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (trade.side == TradeSide.BUY) "录入确认净值，系统将按买入金额自动计算份额。" else "录入确认净值，完成赎回核算。", color = Muted)
                    NumericField(confirmPrice, { confirmPrice = it }, "确认净值 / 克价", Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = {
                runCatching { ledger.confirmTrade(trade.id, BigDecimal(confirmPrice)) }
                    .onSuccess { onChanged(); confirmTarget = null }
                    .onFailure { message = it.message ?: "确认失败"; messageIsError = true; confirmTarget = null }
            }) { Text("确认并计算") } },
            dismissButton = { TextButton(onClick = { confirmTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun NumericField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value, onValueChange, modifier = modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = RoundedCornerShape(15.dp), colors = fieldColors()
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Ink, unfocusedTextColor = Ink,
    focusedContainerColor = White, unfocusedContainerColor = White,
    focusedBorderColor = Blue, unfocusedBorderColor = Color(0xFFD8D8D2),
    focusedLabelColor = Ink, unfocusedLabelColor = Muted, cursorColor = Ink
)

@Composable
private fun AssetsScreen(
    positions: List<Position>, trades: List<Trade>, ledger: Ledger, onChanged: () -> Unit,
    onExport: () -> Unit, onImport: () -> Unit, dataMessage: String, dataMessageIsError: Boolean,
    modifier: Modifier = Modifier
) {
    var showAllTrades by remember { mutableStateOf(false) }
    var reversalTarget by remember { mutableStateOf<Trade?>(null) }
    var reversalReason by remember { mutableStateOf("") }
    var reversalError by remember { mutableStateOf<String?>(null) }
    var reversalMessage by remember { mutableStateOf("") }
    ScreenColumn(modifier) {
        BrandHeader("资产")
        Text("持仓按确认流水计算。补充当前净值或克价后，账面市值与盈亏会同步更新。", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
        if (positions.isEmpty()) EmptyCard("这里还没有资产", "先到“记账”页搜索基金名称，按买入金额记下申购。")
        positions.forEach { position ->
            PositionCard(position)
            QuoteEditor(position.asset, position.quote, ledger) { onChanged() }
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp), colors = CardDefaults.cardColors(containerColor = White)) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("交易流水", Modifier.weight(1f), color = Ink, fontWeight = FontWeight.SemiBold)
                    Text("${trades.size} 笔", color = Muted, fontSize = 11.sp)
                }
                if (trades.isEmpty()) {
                    Text("记下买入、赎回或期初持仓后，会在这里保留记录。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                } else {
                    trades.take(if (showAllTrades) trades.size else 5).forEach { trade ->
                        TradeHistoryRow(trade) {
                            reversalTarget = trade
                            reversalReason = ""
                            reversalError = null
                        }
                    }
                    if (trades.size > 5) {
                        TextButton(onClick = { showAllTrades = !showAllTrades }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (showAllTrades) "收起流水" else "查看全部 ${trades.size} 笔")
                        }
                    }
                }
                if (reversalMessage.isNotBlank()) Text(reversalMessage, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp), colors = CardDefaults.cardColors(containerColor = White)) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("账本数据与备份", color = Ink, fontWeight = FontWeight.SemiBold)
                Text("${trades.size} 笔交易 · ${ledger.assets().size} 个可选标的", color = Muted, fontSize = 12.sp)
                Text("数据保存在本机。导出文件可用于换机或恢复；文件内含持仓和交易金额。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onExport, shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = Ink)) {
                        Text("导出备份")
                    }
                    TextButton(onClick = onImport) { Text("恢复备份", color = Ink) }
                }
                if (dataMessage.isNotBlank()) Text(dataMessage, color = if (dataMessageIsError) ErrorColor else Ink, fontSize = 12.sp)
            }
        }
    }
    reversalTarget?.let { trade ->
        AlertDialog(
            onDismissRequest = { reversalTarget = null },
            title = { Text(if (trade.confirmed) "冲正这笔交易？" else "作废这笔待确认流水？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "原流水会保留并标记冲正，持仓和现金会按剩余有效流水重算。${trade.day} 起的旧观察快照将从分析中停用；若后续赎回因此超过持仓或现金变成负数，冲正会被拒绝。",
                        color = Muted, fontSize = 12.sp, lineHeight = 18.sp
                    )
                    OutlinedTextField(
                        reversalReason,
                        { reversalReason = it.take(200); reversalError = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("冲正原因 · 必填") },
                        placeholder = { Text("例如：份额录入错误") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors()
                    )
                    reversalError?.let { Text(it, color = ErrorColor, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = reversalReason.isNotBlank(),
                    onClick = {
                        runCatching { ledger.reverseTrade(trade.id, reversalReason) }
                            .onSuccess {
                                reversalMessage = "流水已冲正；受影响的历史快照已停用，后续观察将重新建立。"
                                reversalTarget = null
                                reversalReason = ""
                                onChanged()
                            }
                            .onFailure { reversalError = it.message ?: "冲正失败，请检查账本状态" }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = ErrorColor)
                ) { Text(if (trade.confirmed) "确认冲正" else "确认作废") }
            },
            dismissButton = { TextButton(onClick = { reversalTarget = null }) { Text("保留流水") } }
        )
    }
}

@Composable
private fun TradeHistoryRow(trade: Trade, onReverse: () -> Unit) {
    val action = when (trade.side) {
        TradeSide.BUY -> "买入"
        TradeSide.SELL -> "赎回"
        TradeSide.OPENING_POSITION -> "期初持仓"
    }
    val unit = if (trade.asset.kind == AssetKind.GOLD) "克" else "份"
    val details = when {
        !trade.confirmed -> "待确认 · 买入金额 ¥${trade.amount.money()}"
        trade.side == TradeSide.OPENING_POSITION -> "持有 ${trade.units?.qty() ?: "—"} $unit · 历史成本 ¥${trade.amount.money()}"
        else -> buildString {
            append("${trade.units?.qty() ?: "—"} $unit")
            trade.price?.let { append(" × ${it.setScale(4, RoundingMode.HALF_UP).toPlainString()}") }
            if (trade.fee.signum() > 0) append(" · 手续费 ¥${trade.fee.money()}")
        }
    }.let { detail -> if (trade.isReversed) "$detail · 原因：${trade.reversalReason}" else detail }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(trade.asset.name, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            val status = when {
                trade.isReversed -> "已冲正"
                trade.confirmed -> "已确认"
                else -> "待确认"
            }
            Text("${trade.day} · $action · $status", color = Muted, fontSize = 10.sp)
            Text(details, color = Muted, fontSize = 10.sp, maxLines = 2, lineHeight = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("¥${trade.amount.money()}", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (!trade.isReversed) TextButton(
                onClick = onReverse,
                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)
            ) { Text(if (trade.confirmed) "冲正" else "作废", color = ErrorColor, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun QuoteEditor(asset: AssetInfo, current: BigDecimal?, ledger: Ledger, onSaved: () -> Unit) {
    var value by remember(asset.symbol, current) { mutableStateOf(current?.toPlainString() ?: "") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        NumericField(value, { value = it }, if (asset.kind == AssetKind.GOLD) "当前克价（元）" else "当前净值（元）", Modifier.weight(1f))
        Button(onClick = {
            runCatching { ledger.setQuote(asset, BigDecimal(value)) }.onSuccess { onSaved() }
        }, shape = RoundedCornerShape(12.dp)) { Text("更新") }
    }
    Text(if (current == null) "尚无价格；未报价资产不计入总市值" else "上次报价 ${asset.symbol}  ·  手动来源", color = Muted, fontSize = 10.sp)
}

@Composable
private fun AnalysisScreen(
    positions: List<Position>, trades: List<Trade>, cashEvents: List<AccountCashEvent>, cost: BigDecimal,
    holding: BigDecimal, realized: BigDecimal,
    snapshots: List<PortfolioSnapshot>, positionSnapshots: Map<LocalDate, List<PositionSnapshot>>,
    invalidatedSnapshotCount: Int,
    onRecordSnapshot: () -> Unit, modifier: Modifier = Modifier
) {
    val pending = trades.count { !it.confirmed && !it.isReversed }
    val today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
    val currentValue = positions.fold(BigDecimal.ZERO) { sum, p -> sum + (p.value ?: BigDecimal.ZERO) }
    val cashBalance = AccountCashLedger.balance(cashEvents)
    val accountValue = if (cashBalance != null && positions.all { it.value != null }) currentValue + cashBalance.amount else null
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
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = White),
            border = BorderStroke(1.dp, Color(0xFFD8D8D2))) {
            Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("账面表现", color = Muted, fontSize = 12.sp)
                Text("${if (holding.add(realized).signum() > 0) "+" else ""}¥${holding.add(realized).money()}", color = plColor(holding.add(realized)), fontSize = 29.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallStat("账户总资产", accountValue?.let { "¥${it.money()}" } ?: if (cashBalance == null) "未启用" else "待补价格", Modifier.weight(1f))
                    SmallStat("可用现金", cashBalance?.let { "¥${it.amount.money()}" } ?: "—", Modifier.weight(1f))
                }
                Text("当前持仓市值 ¥${currentValue.money()}  ·  持仓浮盈 ¥${holding.money()}  ·  已实现 ¥${realized.money()}", color = Muted, fontSize = 11.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallStat("全部流水", "${trades.size} 笔", Modifier.weight(1f))
            SmallStat("待确认", "$pending 笔", Modifier.weight(1f))
        }
        if (pending > 0) InfoCard("$pending 笔交易待确认", "待确认交易暂不计入份额和已确认周期统计。", Blue)
        Text("资产走势", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
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
        Text("资产规模变化会受到入金和出金影响；累计盈亏来自已记录的持仓快照。", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("资产配置", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(if (cashBalance == null) "当前持仓 · 不含现金" else "账户持仓与现金", color = Muted, fontSize = 10.sp)
            }
        }
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
                Text("按每日观察快照计算；报价缺失时不补零", color = Muted, fontSize = 10.sp)
            }
            TextButton(onClick = onRecordSnapshot) { Text("记录观察点") }
        }
        Text("已记录 ${snapshots.size} 个有效观察日 · Modified Dietz 为资金流调整近似值；未启用现金跟踪时按持仓口径计算", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
        ReportPeriod.entries.forEach { period ->
            val facts = PortfolioAnalytics.facts(period, today, snapshots, positionSnapshots, trades, cashEvents)
            PeriodReportCard(facts, snapshots, positionSnapshots)
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

    Card(
        Modifier.fillMaxWidth().animateContentSize(tween(260)),
        shape = TacticalPanel,
        colors = CardDefaults.cardColors(containerColor = White),
        border = BorderStroke(1.dp, Color(0xFFD8D8D2)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    Text(changeLabel, color = change?.let(::plColor) ?: Muted, fontSize = 10.sp)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TrendMetric.entries.forEach { item ->
                    FilterChip(
                        selected = metric == item,
                        onClick = { onMetricChange(item) },
                        label = { Text(item.label) }, colors = assetChipColors()
                    )
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TrendRange.entries.forEach { item ->
                    FilterChip(
                        selected = range == item,
                        onClick = { onRangeChange(item) },
                        label = { Text(item.label) }, colors = assetChipColors()
                    )
                }
            }
            if (points.size >= 2) {
                PortfolioTrendChart(points, activeIndex, onPointSelected, Modifier.fillMaxWidth().height(154.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(first?.day?.toString() ?: "", Modifier.weight(1f), color = Muted, fontSize = 9.sp)
                    Text("${points.size} 个有效观察点", color = Muted, fontSize = 9.sp)
                    Text(last?.day?.toString() ?: "", Modifier.weight(1f), color = Muted, fontSize = 9.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
                Text("拖动曲线查看单日记录；缺少价格的观察点不会补零。", color = Muted, fontSize = 10.sp)
            } else {
                Column(
                    Modifier.fillMaxWidth().height(118.dp).background(Paper, RoundedCornerShape(14.dp)).padding(15.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(if (points.isEmpty()) "暂无可绘制的观察数据" else "已记录 1 个观察点", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        if (points.isEmpty()) "记下交易并补充价格后，曲线会从有效快照开始。" else "再积累一个有效观察日即可查看变化曲线。",
                        color = Muted, fontSize = 11.sp, lineHeight = 16.sp
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
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
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
                    selectAt(down.position.x)
                    do {
                        val event = awaitPointerEvent()
                        val changeEvent = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (changeEvent.pressed) {
                            selectAt(changeEvent.position.x)
                            changeEvent.consume()
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        val left = 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 9.dp.toPx()
        repeat(4) { row ->
            val y = top + (bottom - top) * row / 3f
            drawLine(Color(0xFFE9E7E1), Offset(left, y), Offset(right, y), 1.dp.toPx())
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
            Color(0xFFAAA9A2), Offset(activeX, top), Offset(activeX, bottom), 1.dp.toPx(),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        )
        drawCircle(White, radius = 6.dp.toPx(), center = Offset(activeX, activeY))
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
                    Text("$basisLabel · ${slices.size} 项", color = Muted, fontSize = 10.sp)
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
                                .background(if (selected) Paper else Color.Transparent)
                                .clickable { onSliceSelected(slice.id) }
                                .padding(horizontal = 5.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(Modifier.size(8.dp), shape = CircleShape, color = slice.color) {}
                            Spacer(Modifier.width(7.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(slice.name, color = Ink, fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                                Text("¥${slice.value.money()}", color = Muted, fontSize = 9.sp, maxLines = 1)
                            }
                            Text("${percent.toPlainString()}%", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Text("点选图例可聚焦单项；市值口径要求全部持仓有有效价格。", color = Muted, fontSize = 10.sp)
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
            Text(selected?.name ?: "账户总额", color = Muted, fontSize = 9.sp, maxLines = 1)
            Text("¥${(selected?.value ?: total).money()}", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (selected != null) {
                val percent = selected.value.multiply(BigDecimal("100")).divide(total, 1, RoundingMode.HALF_UP)
                Text("${percent.toPlainString()}%", color = selected.color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
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

@Composable
private fun PeriodReportCard(
    facts: PeriodFacts,
    snapshots: List<PortfolioSnapshot>,
    positionSnapshots: Map<LocalDate, List<PositionSnapshot>>
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
                    Text("${facts.start} — ${facts.end}", color = Muted, fontSize = 10.sp)
                }
                Text(if (expanded) "收起⌃" else "详情⌄", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(if (accountBased) "账户区间盈亏" else "持仓区间盈亏", color = Muted, fontSize = 10.sp)
                    Text(
                        displayProfit?.let { "${if (it.signum() > 0) "+" else ""}¥${it.money()}" } ?: "暂不可算",
                        color = displayProfit?.let(::plColor) ?: Muted, fontSize = 21.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(if (accountBased) "账户 Modified Dietz" else "持仓 Modified Dietz", color = Muted, fontSize = 10.sp)
                    Text(displayRate?.let { "${if (it.signum() > 0) "+" else ""}${it.toPlainString()}%" } ?: "—",
                        color = displayRate?.let(::plColor) ?: Muted, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (series.size >= 2) PortfolioProfitSparkline(series, Modifier.fillMaxWidth().height(46.dp))
            if (facts.missingReason != null) {
                Text(requireNotNull(facts.missingReason), color = Blue, fontSize = 11.sp, lineHeight = 16.sp)
            } else if (!accountBased) {
                Text("当前为持仓口径；${facts.accountMissingReason ?: "完整账户快照不可用"}", color = Blue, fontSize = 10.sp, lineHeight = 15.sp)
            } else if (facts.accountProfit == null) {
                Text(facts.accountMissingReason ?: "账户估值数据暂不可用", color = Blue, fontSize = 10.sp, lineHeight = 15.sp)
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
                    color = Muted, fontSize = 10.sp)
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
                    if (facts.contributors.isNotEmpty()) {
                        Text("盈亏贡献 · 由持仓利润快照差分", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        val endRowsByName = positionSnapshots[closing?.day]?.associateBy { it.asset.name }.orEmpty()
                        facts.contributors.take(3).forEach { contribution ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                val quote = endRowsByName[contribution.assetName]
                                Text(
                                    contribution.assetName + (quote?.quoteSource?.let { " · $it" } ?: ""),
                                    Modifier.weight(1f), color = Ink, fontSize = 11.sp, maxLines = 1
                                )
                                Text("${if (contribution.amount.signum() > 0) "+" else ""}¥${contribution.amount.money()}",
                                    color = plColor(contribution.amount), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
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
                            Text("账户配置暂不可用 · ${facts.accountAllocationMissingReason}", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                        } else {
                            if (facts.period != ReportPeriod.DAY) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SmallStat("账户 HHI", facts.accountAllocationHhi?.toPlainString() ?: "—", Modifier.weight(1f))
                                    SmallStat("有效资产数", facts.accountEffectiveAssetCount?.toPlainString() ?: "—", Modifier.weight(1f))
                                }
                            }
                            if (facts.period == ReportPeriod.MONTH) {
                                Text("账户配置 · 含现金", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                facts.accountAllocations.take(5).forEach { allocation ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(allocation.name, Modifier.weight(1f), color = Ink, fontSize = 11.sp, maxLines = 1)
                                        Text("${allocation.weightPct.toPlainString()}% · ¥${allocation.marketValue.money()}",
                                            color = Muted, fontSize = 10.sp)
                                    }
                                }
                            }
                            Text(if (facts.period == ReportPeriod.DAY) "相较昨日配置变化 · 百分点" else "期初至期末权重变化 · 百分点",
                                color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            if (facts.accountAllocationChanges.isNotEmpty()) {
                                facts.accountAllocationChanges.take(3).forEach { change ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(change.name, Modifier.weight(1f), color = Ink, fontSize = 10.sp, maxLines = 1)
                                        Text("${if (change.changePercentagePoints.signum() > 0) "+" else ""}${change.changePercentagePoints.toPlainString()} pp",
                                            color = plColor(change.changePercentagePoints), fontSize = 10.sp)
                                    }
                                }
                            } else Text("权重没有变化", color = Muted, fontSize = 10.sp)
                        }
                    }
                    Text(if (accountBased) "账户盈亏已扣除外部净入金；Modified Dietz 按日期加权近似。TWR 与回撤使用逐日流量调整收益；回撤要求每日快照连续、报价新鲜且流水估值完整。月度 XIRR 按实际天数年化。" else "当前仅能核算持仓口径。启用现金跟踪并积累完整账户快照后，才计算账户盈亏、收益率和 XIRR。", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun PortfolioProfitSparkline(snapshots: List<PortfolioSnapshot>, modifier: Modifier = Modifier) {
    val accountBased = snapshots.all { it.accountComplete }
    val values = if (accountBased && snapshots.isNotEmpty()) {
        val firstValue = requireNotNull(snapshots.first().totalValue)
        var cumulativeExternalFlow = BigDecimal.ZERO
        snapshots.mapIndexedNotNull { index, snapshot ->
            if (index > 0) cumulativeExternalFlow += snapshot.externalNetFlow ?: return@mapIndexedNotNull null
            (requireNotNull(snapshot.totalValue) - firstValue - cumulativeExternalFlow).toDouble()
        }
    } else snapshots.mapNotNull { it.totalProfit?.toDouble() }
    if (values.size < 2) return
    val min = minOf(0.0, values.minOrNull() ?: 0.0)
    val max = maxOf(0.0, values.maxOrNull() ?: 0.0)
    val range = (max - min).coerceAtLeast(0.01)
    val color = if ((values.lastOrNull() ?: 0.0) >= (values.firstOrNull() ?: 0.0)) MarketUp else MarketDown
    Canvas(modifier) {
        val zeroY = size.height - ((0.0 - min) / range).toFloat() * size.height
        drawLine(Color(0xFFE8E5DE), Offset(0f, zeroY), Offset(size.width, zeroY), 1.dp.toPx())
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.lastIndex).coerceAtLeast(1)
            val y = size.height - ((value - min) / range).toFloat() * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        val lastY = size.height - ((values.last() - min) / range).toFloat() * size.height
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(size.width, lastY))
    }
}
