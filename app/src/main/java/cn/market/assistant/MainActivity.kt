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
import cn.market.assistant.domain.portfolio.Trade

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Paper = Color(0xFFF2F1ED)
internal val Ink = Color(0xFF20272A)
internal val Muted = Color(0xFF747D80)
internal val Blue = Color(0xFF9E6B39)
internal val SoftBlue = Color(0xFFF0E8DC)
internal val Gold = Color(0xFFB89561)
internal val White = Color(0xFFFCFBF7)
internal val ErrorColor = Color(0xFFB8424B)
internal val MarketUp = Color(0xFFC3444D)
internal val MarketDown = Color(0xFF168568)
private val HeroUp = Color(0xFFFF8D92)
private val HeroDown = Color(0xFF72D5AA)
private val HeroInk = Color(0xFF243332)
internal val TacticalPanel = RoundedCornerShape(20.dp)
internal val LocalOpenDrawer = staticCompositionLocalOf<() -> Unit> { {} }
private const val GOLD_ROUTE = "__gold_dashboard__"
private const val HEALTH_ROUTE = "__data_health__"

private fun readBackupText(inputStream: InputStream): String = inputStream.use { input ->
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var totalBytes = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) {
            val byte = input.read()
            if (byte < 0) break
            require(totalBytes < EncryptedBackup.MAX_FILE_BYTES) { "备份文件超过 128 MB" }
            output.write(byte)
            totalBytes++
            continue
        }
        require(count <= EncryptedBackup.MAX_FILE_BYTES - totalBytes) { "备份文件超过 128 MB" }
        output.write(buffer, 0, count)
        totalBytes += count
    }
    String(output.toByteArray(), Charsets.UTF_8)
}

private enum class Tab(val label: String, val icon: String) {
    HOME("总览", "home"), WATCHLIST("自选", "star"), RECORD("记账", "plus"), ASSETS("持仓", "assets"), ANALYSIS("分析", "chart")
}


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DailyPortfolioSnapshotScheduler.ensureScheduled(applicationContext)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.statusBarColor = android.graphics.Color.rgb(242, 241, 237)
        window.navigationBarColor = android.graphics.Color.rgb(28, 34, 37)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Blue, onPrimary = Color.White, secondary = Gold,
                background = Paper, surface = White, onSurface = Ink,
                onBackground = Ink, error = ErrorColor,
                secondaryContainer = SoftBlue, onSecondaryContainer = Ink,
                surfaceVariant = Color(0xFFECEBE6), outline = Color(0xFFD6D5CF)
            )) {
                LedgerStartup { ledger -> MarketApp(ledger) }
            }
        }
    }
}

@Composable
private fun LedgerStartup(content: @Composable (Ledger) -> Unit) {
    val context = LocalContext.current.applicationContext
    var attempt by remember { mutableStateOf(0) }
    var ledger by remember { mutableStateOf<Ledger?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(attempt) {
        ledger = null
        errorMessage = null
        try {
            ledger = withContext(Dispatchers.IO) { Ledger(context) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            errorMessage = error.message ?: "无法打开本地账本，请重试或检查可用空间"
        }
    }

    val openedLedger = ledger
    if (openedLedger != null) {
        content(openedLedger)
    } else {
        LedgerStartupScreen(errorMessage) { attempt++ }
    }
}

@Composable
private fun LedgerStartupScreen(errorMessage: String?, onRetry: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Market", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                if (errorMessage == null) {
                    CircularProgressIndicator(color = Blue)
                    Text("正在安全打开本地账本…", color = Muted, fontSize = 14.sp)
                } else {
                    Text("本地账本暂时无法打开", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(errorMessage, color = ErrorColor, fontSize = 13.sp)
                    Button(onClick = onRetry) { Text("重试") }
                }
            }
        }
    }
}

internal fun plColor(value: BigDecimal) = when (value.signum()) {
    1 -> MarketUp
    -1 -> MarketDown
    else -> Muted
}

@Composable
internal fun assetChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = SoftBlue,
    selectedLabelColor = Ink,
    containerColor = White,
    labelColor = Muted
)

@Composable
private fun MarketApp(ledger: Ledger) {
    val marketRepository = remember(ledger) { MarketRepositoryImpl(ledger, Dispatchers.IO) }
    val fundRepository = remember(ledger) { FundRepositoryImpl(ledger, Dispatchers.IO) }
    val marketBoardState = rememberMarketBoardFeatureState(marketRepository)
    val dataHealthState = rememberDataHealthFeatureState(marketRepository)
    var tab by remember { mutableStateOf(Tab.HOME) }
    var concealOverviewAmounts by remember { mutableStateOf(false) }
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
    var restorePayload by remember { mutableStateOf<String?>(null) }
    var restorePayloadEncrypted by remember { mutableStateOf(false) }
    var encryptedRestorePayload by remember { mutableStateOf<String?>(null) }
    var restorePassword by remember { mutableStateOf("") }
    var restorePasswordError by remember { mutableStateOf<String?>(null) }
    var unlockingBackup by remember { mutableStateOf(false) }
    var restoringBackup by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordConfirmation by remember { mutableStateOf("") }
    var exportPasswordError by remember { mutableStateOf<String?>(null) }
    var showExportBackupDialog by remember { mutableStateOf(false) }
    var exportingBackup by remember { mutableStateOf(false) }
    var pendingBackupPayload by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    suspend fun syncLinkedPortfolioQuotes(quotes: Map<String, MarketQuote>) {
        val changed = withContext(Dispatchers.IO) { ledger.syncLinkedMarketQuotes(quotes) }
        if (changed > 0) revision++
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val payload = pendingBackupPayload
        pendingBackupPayload = null
        if (uri != null && payload != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val output = context.contentResolver.openOutputStream(uri) ?: error("无法创建备份文件")
                    output.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                }
            }
            result.onSuccess { dataMessage = "加密账本备份已导出"; dataMessageIsError = false }
                .onFailure { dataMessage = it.message ?: "导出失败"; dataMessageIsError = true }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    readBackupText(context.contentResolver.openInputStream(uri) ?: error("无法读取备份文件"))
                }
            }
            result.onSuccess { payload ->
                dataMessage = ""
                dataMessageIsError = false
                if (ledger.isEncryptedBackup(payload)) {
                    encryptedRestorePayload = payload
                    restorePassword = ""
                    restorePasswordError = null
                } else {
                    restorePayload = payload
                    restorePayloadEncrypted = false
                }
            }
                .onFailure { error -> dataMessage = error.message ?: "读取备份失败"; dataMessageIsError = true }
        }
    }
    fun refreshMarket() {
        if (marketLoading) return
        scope.launch {
            marketLoading = true
            runCatching { marketRepository.refreshOverview() }
                .onSuccess { result ->
                    marketQuotes = result.quotes
                    syncLinkedPortfolioQuotes(result.quotes)
                    marketMessage = when {
                        result.refreshedCount == 0 -> "行情源未返回数据，显示最近缓存"
                        result.coverageIncomplete -> "部分市场暂时没有可用报价；有数据的市场已显示，恢复后可刷新"
                        else -> ""
                    }
                }.onFailure {
                    marketQuotes = marketRepository.cachedQuotes()
                    syncLinkedPortfolioQuotes(marketQuotes)
                    marketMessage = if (marketQuotes.isEmpty()) "行情暂不可用，请稍后重试" else "刷新失败，显示本机缓存"
                }
            dataHealthState.reloadDiagnostics()
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
                    syncLinkedPortfolioQuotes(result.quotes)
                    goldMessage = when {
                        result.refreshedCount == 0 -> "国内与国际黄金行情暂不可用，显示最近缓存"
                        result.sourceFailureCount > 0 -> "部分金价源暂不可用；每项价格保留独立来源和时间"
                        else -> ""
                    }
                }.onFailure {
                    marketQuotes = marketRepository.cachedQuotes()
                    syncLinkedPortfolioQuotes(marketQuotes)
                    goldMessage = "金价刷新失败，显示本机缓存"
            }
            dataHealthState.reloadDiagnostics()
            goldLoading = false
        }
    }
    LaunchedEffect(selectedMarket) {
        if (selectedMarket == HEALTH_ROUTE) {
            dataHealthState.reloadDiagnostics()
        }
    }
    LaunchedEffect(marketRepository) {
        marketQuotes = marketRepository.cachedQuotes()
        syncLinkedPortfolioQuotes(marketQuotes)
        refreshMarket()
    }
    val positions = remember(revision) { ledger.positions() }
    LaunchedEffect(positions, revision) {
        val now = Instant.now()
        val nextExpiry = positions.asSequence()
            .mapNotNull { it.manualGoldQuote?.validUntil }
            .mapNotNull { runCatching { Instant.parse(it) }.getOrNull() }
            .filter { it.isAfter(now) }
            .minOrNull()
        if (nextExpiry != null) {
            val waitMillis = java.time.Duration.between(Instant.now(), nextExpiry).toMillis().coerceAtLeast(1)
            delay(waitMillis)
            revision++
        }
    }
    val trades = remember(revision) { ledger.trades() }
    val cashEvents = remember(revision) { ledger.accountCashEvents() }
    var snapshotRevision by remember { mutableStateOf(0) }
    LaunchedEffect(positions, cashEvents, trades, revision) {
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
                    dataSourceRuns = dataHealthState.dataSourceRuns,
                    dailyHistoryCoverage = dataHealthState.dailyHistoryCoverage,
                    backfillLoadingIds = dataHealthState.backfillLoadingIds,
                    backfillStatus = dataHealthState.backfillStatus,
                    healthChecking = dataHealthState.healthChecking,
                    healthCheck = dataHealthState.healthCheck,
                    onRunCheck = {
                        dataHealthState.runHealthCheck { quotes ->
                            marketQuotes = quotes
                            scope.launch { syncLinkedPortfolioQuotes(quotes) }
                        }
                    },
                    onBackfillDaily = dataHealthState::backfillDailyHistory,
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
                ) else if (route.third) MarketBoardExplorerScreen(
                    snapshots = marketBoardState.snapshots,
                    loadingTypes = marketBoardState.loadingTypes,
                    message = marketBoardState.message,
                    marketRepository = marketRepository,
                    onRefresh = marketBoardState::refresh,
                    onBack = { showSectors = false },
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    modifier = Modifier.padding(insets)
                ) else OverviewScreen(
                    positions, pending.size, totalValue, totalCost, holding, realized, accountValue,
                    cashBalance != null, todayFacts, portfolioSnapshots,
                    marketQuotes, marketLoading, marketMessage, ::refreshMarket, marketBoardState.snapshots,
                    marketBoardState.loadingTypes, marketBoardState.message, marketBoardState::refreshAll,
                    concealAmounts = concealOverviewAmounts,
                    onToggleAmountVisibility = { concealOverviewAmounts = !concealOverviewAmounts },
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
                        ledger.watchlist(), fundRepository, marketQuotes, marketLoading, marketMessage, ::refreshMarket,
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
                    ledger, fundRepository, marketRepository, marketQuotes, pending, cashBalance, cashEvents,
                    snackbarHostState, { revision++ }, Modifier.padding(insets)
                )
                Tab.ASSETS -> AssetsScreen(
                    positions, trades, ledger, marketRepository, marketQuotes,
                    onMarketQuoteFetched = { quote ->
                        val updatedQuotes = marketQuotes + (quote.secid to quote)
                        marketQuotes = updatedQuotes
                        scope.launch { syncLinkedPortfolioQuotes(updatedQuotes) }
                    },
                    onChanged = {
                        revision++
                        scope.launch { syncLinkedPortfolioQuotes(marketQuotes) }
                    },
                    onExport = {
                        exportPassword = ""
                        exportPasswordConfirmation = ""
                        exportPasswordError = null
                        showExportBackupDialog = true
                    },
                    onImport = { restoreLauncher.launch(arrayOf("application/json", "text/*")) },
                    dataMessage = dataMessage, dataMessageIsError = dataMessageIsError,
                    modifier = Modifier.padding(insets)
                )
                Tab.ANALYSIS -> AnalysisScreen(
                    positions, trades, cashEvents, totalCost, holding, realized, portfolioSnapshots, positionSnapshots,
                    invalidatedSnapshotCount,
                    marketRepository = marketRepository,
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
        if (showExportBackupDialog) {
            ExportEncryptedBackupDialog(
                password = exportPassword,
                confirmation = exportPasswordConfirmation,
                error = exportPasswordError,
                busy = exportingBackup,
                onPasswordChange = { exportPassword = it.take(256); exportPasswordError = null },
                onConfirmationChange = { exportPasswordConfirmation = it.take(256); exportPasswordError = null },
                onConfirm = {
                    when {
                        exportPassword.length < 12 -> exportPasswordError = "请设置至少 12 个字符的密码。"
                        exportPassword != exportPasswordConfirmation -> exportPasswordError = "两次输入的密码不一致。"
                        exportingBackup -> Unit
                        else -> {
                            val password = exportPassword
                            exportingBackup = true
                            exportPasswordError = null
                            scope.launch {
                                val result = runCatching {
                                    withContext(Dispatchers.IO) { ledger.exportEncryptedBackup(password.toCharArray()) }
                                }
                                result.onSuccess { encrypted ->
                                    pendingBackupPayload = encrypted
                                    showExportBackupDialog = false
                                    dataMessage = ""
                                    backupLauncher.launch("养基助手账本-${java.time.LocalDate.now()}.backup.json")
                                }.onFailure { error ->
                                    exportPasswordError = error.message ?: "加密备份失败"
                                    dataMessage = exportPasswordError.orEmpty()
                                    dataMessageIsError = true
                                }
                                exportPassword = ""
                                exportPasswordConfirmation = ""
                                exportingBackup = false
                            }
                        }
                    }
                },
                onDismiss = {
                    showExportBackupDialog = false
                    exportPassword = ""
                    exportPasswordConfirmation = ""
                    exportPasswordError = null
                }
            )
        }
        encryptedRestorePayload?.let { payload ->
            UnlockEncryptedBackupDialog(
                password = restorePassword,
                error = restorePasswordError,
                busy = unlockingBackup,
                onPasswordChange = { restorePassword = it.take(256); restorePasswordError = null },
                onConfirm = {
                    if (!unlockingBackup) {
                        val password = restorePassword
                        unlockingBackup = true
                        restorePasswordError = null
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) { ledger.decryptBackup(payload, password.toCharArray()) }
                            }
                            result.onSuccess { plaintext ->
                                restorePayload = plaintext
                                restorePayloadEncrypted = true
                                encryptedRestorePayload = null
                                restorePassword = ""
                            }.onFailure { error ->
                                restorePasswordError = error.message ?: "无法解锁备份"
                                restorePassword = ""
                            }
                            unlockingBackup = false
                        }
                    }
                },
                onDismiss = {
                    encryptedRestorePayload = null
                    restorePassword = ""
                    restorePasswordError = null
                }
            )
        }
        restorePayload?.let { payload ->
            AlertDialog(
                onDismissRequest = { if (!restoringBackup) restorePayload = null },
                title = { Text("恢复账本备份？") },
                text = {
                    Text(
                        if (restorePayloadEncrypted) {
                            "备份已解锁。恢复会替换本机流水、自定义标的和手动报价，无法撤销；建议先导出当前账本。"
                        } else {
                            "这是旧版未加密备份。恢复会替换本机流水、自定义标的和手动报价，无法撤销；建议先导出新的加密备份。"
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !restoringBackup,
                        onClick = {
                            if (!restoringBackup) {
                                restoringBackup = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { ledger.restoreBackup(payload) }
                                    }
                                    result.onSuccess {
                                        revision++
                                        dataMessage = "账本已恢复"
                                        dataMessageIsError = false
                                    }.onFailure { error ->
                                        dataMessage = error.message ?: "恢复失败"
                                        dataMessageIsError = true
                                    }
                                    restorePayload = null
                                    restorePayloadEncrypted = false
                                    restoringBackup = false
                                }
                            }
                        }
                    ) { Text(if (restoringBackup) "正在恢复…" else "替换并恢复") }
                },
                dismissButton = {
                    TextButton(
                        enabled = !restoringBackup,
                        onClick = { restorePayload = null; restorePayloadEncrypted = false }
                    ) { Text("取消") }
                },
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
internal fun ScreenColumn(
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
internal fun BrandHeader(title: String, trailing: String = "") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = Ink, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
        if (trailing.isNotEmpty()) Surface(shape = CircleShape, color = SoftBlue) {
            Text(trailing, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Ink, fontSize = 12.sp)
        }
        DrawerMenuButton()
    }
}

@Composable
internal fun DrawerMenuButton() {
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
                                Text("${position.units.portfolioQty()} 克 · 成本 ¥${position.cost.portfolioMoney()}", color = Muted, fontSize = 10.sp)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    position.value?.let { "市值 ¥${it.portfolioMoney()}" }
                                        ?: if (position.quoteExpired) "报价已过期" else "需录入克价",
                                    color = if (position.quoteExpired) ErrorColor else Ink, fontSize = 11.sp
                                )
                                Text(position.holdingProfit?.let { "盈亏 ${if (it.signum() > 0) "+" else ""}¥${it.portfolioMoney()}" } ?: "估值待补",
                                    color = position.holdingProfit?.let(::plColor) ?: Muted, fontSize = 10.sp)
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
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = style == MarketChartStyle.LINE, onClick = { style = MarketChartStyle.LINE }, label = { Text("走势") }, colors = assetChipColors())
                    FilterChip(selected = style == MarketChartStyle.CANDLE, onClick = { style = MarketChartStyle.CANDLE }, label = { Text("K线") }, colors = assetChipColors())
                    FilterChip(selected = showMa5, onClick = { showMa5 = !showMa5 }, label = { Text("MA5") }, colors = assetChipColors())
                    FilterChip(selected = showMa20, onClick = { showMa20 = !showMa20 }, label = { Text("MA20") }, colors = assetChipColors())
                    FilterChip(selected = showVolume, onClick = { showVolume = !showVolume }, label = { Text("成交量") }, colors = assetChipColors())
                    FilterChip(selected = showRsi, onClick = { showRsi = !showRsi }, label = { Text("RSI14") }, colors = assetChipColors())
                    FilterChip(selected = showMacd, onClick = { showMacd = !showMacd }, label = { Text("MACD") }, colors = assetChipColors())
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
            colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text("查看 518880 完整行情与指标") }
        if (message.isNotBlank()) Text(message, color = Muted, fontSize = 11.sp)
        Text("国际金价直接显示 XAU/USD 美元 / 金衡盎司报价。国内溢价比较会另按汇率和金衡盎司重量折算为人民币 / 克；ETF 图表仅观察国内交易品种走势。行情可能延迟。", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun OverviewPortfolioCard(
    positions: List<Position>,
    total: BigDecimal,
    holding: BigDecimal,
    cost: BigDecimal,
    realized: BigDecimal,
    accountValue: BigDecimal?,
    cashTrackingEnabled: Boolean,
    todayProfit: BigDecimal?,
    todayRate: BigDecimal?,
    todayMissingReason: String?,
    accountPeriod: Boolean,
    allPositionsPriced: Boolean,
    noPositionQuotes: Boolean,
    quoteCount: Int,
    latestQuoteAt: String?,
    concealAmounts: Boolean,
    onToggleAmountVisibility: () -> Unit,
    onRecord: () -> Unit
) {
    val headline = when {
        cashTrackingEnabled && accountValue == null -> "待补价格"
        positions.isEmpty() && accountValue == null -> "尚未录入持仓"
        accountValue == null && noPositionQuotes -> "暂无有效报价"
        else -> "¥${(accountValue ?: total).portfolioMoney()}"
    }
    val hasPrivateAmount = accountValue != null || positions.any { it.value != null }
    val amountHidden = concealAmounts && hasPrivateAmount
    val subtitle = when {
        accountValue != null -> "现金跟踪已启用，所有持仓均有报价"
        cashTrackingEnabled && noPositionQuotes -> "当前持仓暂无报价，账户总资产暂不可用"
        cashTrackingEnabled && !allPositionsPriced -> "部分持仓缺少报价，账户总资产暂不可用"
        cashTrackingEnabled -> "账户总额暂不可用，检查持仓报价后重试"
        noPositionQuotes -> "当前持仓暂无报价，持仓市值暂不可用"
        !allPositionsPriced -> "部分持仓缺少报价，金额仅统计有报价的持仓"
        else -> "尚未启用现金跟踪；当前金额只包含基金和黄金持仓"
    }
    val quoteStatus = when {
        positions.isEmpty() -> "暂无持仓报价"
        latestQuoteAt != null -> "报价覆盖 $quoteCount/${positions.size} 项  ·  最近 $latestQuoteAt"
        else -> "报价覆盖 $quoteCount/${positions.size} 项  ·  尚无报价时间"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = TacticalPanel,
        colors = CardDefaults.cardColors(containerColor = HeroInk),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(HeroInk, Color(0xFF30423E))))) {
            Canvas(Modifier.matchParentSize()) {
                val center = Offset(size.width * .91f, size.height * .18f)
                val radius = size.height * .60f
                drawCircle(Color.White.copy(alpha = .035f), radius, center)
                drawCircle(
                    Color.White.copy(alpha = .07f), radius * .76f, center,
                    style = Stroke(width = 1.dp.toPx())
                )
                drawArc(
                    color = Gold.copy(alpha = .52f), startAngle = 205f, sweepAngle = 112f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius * .9f, center.y - radius * .9f),
                    size = Size(radius * 1.8f, radius * 1.8f),
                    style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
                )
                drawLine(
                    Color.White.copy(alpha = .045f),
                    Offset(size.width * .70f, 0f), Offset(size.width, size.height * .46f),
                    1.dp.toPx()
                )
            }
            Column(
                Modifier.fillMaxWidth().padding(17.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            when {
                                accountValue != null -> "账户总资产 · 含现金"
                                cashTrackingEnabled -> "账户总资产 · 暂不可用"
                                else -> "持仓市值 · 不含现金"
                            },
                            color = Color.White.copy(alpha = .70f), fontSize = 11.sp
                        )
                        Text("本机账本", color = Gold.copy(alpha = .95f), fontSize = 9.sp, letterSpacing = .5.sp)
                    }
                    Button(
                        onClick = onRecord,
                        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp),
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = HeroInk)
                    ) {
                        Text("＋ 记一笔", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (hasPrivateAmount) AmountVisibilityButton(amountHidden, onToggleAmountVisibility)
                }

                AnimatedContent(
                    targetState = amountHidden,
                    transitionSpec = {
                        (fadeIn(tween(160)) + slideInVertically(tween(200)) { it / 5 }) togetherWith
                            (fadeOut(tween(110)) + slideOutVertically(tween(150)) { -it / 5 })
                    },
                    label = "overview_amount_visibility"
                ) { hidden ->
                    Text(
                        if (hidden) "¥  ••••••" else headline,
                        color = Color.White,
                        fontSize = if (headline.startsWith("¥")) 30.sp else 23.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-.5).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(subtitle, color = Color.White.copy(alpha = .62f), fontSize = 10.sp, lineHeight = 14.sp)

                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = .075f)).padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OverviewMetric(
                        title = if (accountPeriod) "今日账户盈亏" else "今日持仓盈亏",
                        value = if (amountHidden) "••••••" else todayProfit?.let {
                            "${if (it.signum() > 0) "+" else ""}¥${it.portfolioMoney()}"
                        } ?: "暂不可用",
                        detail = if (amountHidden) "••••" else todayRate?.let {
                            "${if (it.signum() > 0) "+" else ""}${it.toPlainString()}%"
                        } ?: todayMissingReason ?: "需完整的昨日与今日快照",
                        valueColor = if (amountHidden) Color.White else todayProfit?.let(::heroPlColor) ?: Color.White.copy(alpha = .75f),
                        modifier = Modifier.weight(1f)
                    )
                    Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(36.dp).background(Color.White.copy(alpha = .14f)))
                    OverviewMetric(
                        title = "持有浮盈",
                        value = if (amountHidden) "••••••" else if (allPositionsPriced) {
                            "${if (holding.signum() > 0) "+" else ""}¥${holding.portfolioMoney()}"
                        } else "暂不可用",
                        detail = if (amountHidden) "••••" else "成本 ¥${cost.portfolioMoney()}",
                        valueColor = if (amountHidden) Color.White else if (allPositionsPriced) heroPlColor(holding) else Color.White.copy(alpha = .75f),
                        modifier = Modifier.weight(1f)
                    )
                    Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(36.dp).background(Color.White.copy(alpha = .14f)))
                    OverviewMetric(
                        title = "已实现盈亏",
                        value = if (amountHidden) "••••••" else "${if (realized.signum() > 0) "+" else ""}¥${realized.portfolioMoney()}",
                        detail = if (amountHidden) "••••" else "按已确认流水",
                        valueColor = if (amountHidden) Color.White else heroPlColor(realized),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Surface(Modifier.size(6.dp), shape = CircleShape, color = Gold) {}
                    Text(quoteStatus, color = Color.White.copy(alpha = .66f), fontSize = 9.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun OverviewMetric(
    title: String,
    value: String,
    detail: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Color.White.copy(alpha = .63f), fontSize = 9.sp, maxLines = 1)
        Text(value, color = valueColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, color = Color.White.copy(alpha = .55f), fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AmountVisibilityButton(hidden: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(if (hidden) -12f else 0f, tween(180), label = "visibility_icon_rotation")
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .10f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (hidden) "显示总资产金额" else "隐藏总资产金额" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(18.dp).graphicsLayer { rotationZ = rotation }) {
            val scale = size.width / 20f
            val eye = Path().apply {
                moveTo(2f * scale, 10f * scale)
                quadraticTo(10f * scale, 2.5f * scale, 18f * scale, 10f * scale)
                quadraticTo(10f * scale, 17.5f * scale, 2f * scale, 10f * scale)
                close()
            }
            drawPath(eye, Color.White.copy(alpha = .90f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(Color.White, 2.1.dp.toPx(), Offset(size.width / 2f, size.height / 2f))
            if (hidden) {
                drawLine(
                    Gold, Offset(size.width * .19f, size.height * .82f), Offset(size.width * .82f, size.height * .18f),
                    1.7.dp.toPx(), StrokeCap.Round
                )
            }
        }
    }
}

private fun heroPlColor(value: BigDecimal): Color = when (value.signum()) {
    1 -> HeroUp
    -1 -> HeroDown
    else -> Color.White.copy(alpha = .80f)
}

@Composable
private fun OverviewScreen(
    positions: List<Position>, pendingCount: Int,
    total: BigDecimal, cost: BigDecimal, holding: BigDecimal, realized: BigDecimal,
    accountValue: BigDecimal?, cashTrackingEnabled: Boolean,
    todayFacts: PeriodFacts, snapshots: List<PortfolioSnapshot>,
    marketQuotes: Map<String, MarketQuote>, marketLoading: Boolean, marketMessage: String, onRefreshMarket: () -> Unit,
    boardSnapshots: Map<MarketBoardType, MarketBoardSnapshot>,
    boardLoadingTypes: Set<MarketBoardType>, boardMessage: String,
    onRefreshBoards: () -> Unit,
    concealAmounts: Boolean, onToggleAmountVisibility: () -> Unit,
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
        OverviewPortfolioCard(
            positions = positions,
            total = total,
            holding = holding,
            cost = cost,
            realized = realized,
            accountValue = accountValue,
            cashTrackingEnabled = cashTrackingEnabled,
            todayProfit = todayProfit,
            todayRate = todayRate,
            todayMissingReason = todayMissingReason,
            accountPeriod = accountPeriod,
            allPositionsPriced = allPositionsPriced,
            noPositionQuotes = noPositionQuotes,
            quoteCount = quoteCount,
            latestQuoteAt = latestQuoteAt,
            concealAmounts = concealAmounts,
            onToggleAmountVisibility = onToggleAmountVisibility,
            onRecord = onRecord
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpenAssets, modifier = Modifier.weight(1f)) { Text("全部持仓 →") }
            TextButton(onClick = onOpenGold, modifier = Modifier.weight(1f)) { Text("黄金行情 →") }
            TextButton(onClick = onOpenWatchlist, modifier = Modifier.weight(1f)) { Text("市场行情 →") }
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
        } else {
            positions.take(5).forEach { PositionCard(it, concealAmounts) }
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
        MarketBoardTicker(boardSnapshots, boardLoadingTypes, boardMessage, onOpenSectors, onRefreshBoards)
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
internal fun SmallStat(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = CutCornerShape(3.dp), colors = CardDefaults.cardColors(containerColor = White)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Muted, fontSize = 12.sp)
            Text(value, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun EmptyCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth(), shape = CutCornerShape(3.dp), colors = CardDefaults.cardColors(containerColor = White)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
            Text(body, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
internal fun InfoCard(title: String, body: String, color: Color) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = SoftBlue)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = color, fontWeight = FontWeight.SemiBold)
            Text(body, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Ink, unfocusedTextColor = Ink,
    focusedContainerColor = White, unfocusedContainerColor = White,
    focusedBorderColor = Blue, unfocusedBorderColor = Color(0xFFD8D8D2),
    focusedLabelColor = Ink, unfocusedLabelColor = Muted, cursorColor = Ink
)
