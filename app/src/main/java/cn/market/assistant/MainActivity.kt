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

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
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
import androidx.compose.material3.darkColorScheme
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

private val Paper: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.paper
internal val Gold: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.warn
internal val ErrorColor: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.up
internal val MarketUp: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.up
internal val MarketDown: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.down
private val HeroUp: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.up
private val HeroDown: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.down
private val HeroInk: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.ink

// Design tokens are read from the active theme so one setting reskins every screen.
internal val Ink: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.ink
internal val Muted: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.muted
internal val Blue: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.accent
internal val SoftBlue: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.accentSoft
internal val White: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.surface
internal val TacticalPanel: RoundedCornerShape
    @Composable @ReadOnlyComposable get() =
        RoundedCornerShape(MarketTheme.geometry.panel, MarketTheme.geometry.card)
internal val LocalOpenDrawer = staticCompositionLocalOf<() -> Unit> { {} }
private const val GOLD_ROUTE = "__gold_dashboard__"
private const val HEALTH_ROUTE = "__data_health__"
private const val ASSISTANT_ROUTE = "__assistant__"
private const val ALERT_ROUTE = "__alerts__"
private const val APPEARANCE_ROUTE = "__appearance__"
private const val UPDATE_ROUTE = "__update__"

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

/** One drawer destination: label, glyph, tab and the optional sub-route it opens. */
private data class DrawerEntry(
    val label: String,
    val icon: String,
    val tab: Tab,
    val route: String? = null,
    val sectors: Boolean = false
)


class MainActivity : ComponentActivity() {
    private val alertRequestId = androidx.compose.runtime.mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeAlertIntent(intent)
        // Scheduling starts after the first frame: WorkManager opens the encrypted ledger on its own
        // thread and used to compete with the splash screen's database open.
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContent {
            val themeStore = rememberMarketThemeStore(LocalContext.current)
            val themeState = remember(themeStore) { themeStore.load() }
            LaunchedEffect(
                themeState.accent, themeState.mode, themeState.density, themeState.fontScale,
                themeState.corner
            ) {
                themeStore.save(themeState)
            }
            MarketThemeProvider(themeState) {
                val colors = MarketTheme.colors
                val dark = MarketTheme.isDark
                LaunchedEffect(colors.paper, dark) {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility =
                        if (dark) 0 else android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    window.statusBarColor = colors.paper.toArgb()
                    window.navigationBarColor = colors.paper.toArgb()
                }
                val scheme = if (dark) darkColorScheme(
                    primary = colors.accent,
                    onPrimary = colors.paper,
                    primaryContainer = colors.accentSoft,
                    onPrimaryContainer = colors.ink,
                    secondary = colors.accent,
                    onSecondary = colors.paper,
                    secondaryContainer = colors.accentSoft,
                    onSecondaryContainer = colors.ink,
                    background = colors.paper,
                    onBackground = colors.ink,
                    surface = colors.surface,
                    onSurface = colors.ink,
                    surfaceVariant = colors.surfaceAlt,
                    onSurfaceVariant = colors.inkSoft,
                    outline = colors.line,
                    error = colors.up,
                    onError = colors.paper,
                    tertiary = colors.warn,
                    onTertiary = colors.paper
                ) else lightColorScheme(
                    primary = colors.accent, onPrimary = Color.White, secondary = colors.accent,
                    background = colors.paper, surface = colors.surface, onSurface = colors.ink,
                    onBackground = colors.ink, error = colors.up,
                    secondaryContainer = colors.accentSoft, onSecondaryContainer = colors.ink,
                    surfaceVariant = colors.surfaceAlt, outline = colors.line
                )
                MaterialTheme(colorScheme = scheme) {
                    LedgerStartup { ledger -> MarketApp(ledger, alertRequestId.intValue) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeAlertIntent(intent)
    }

    private fun consumeAlertIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(AlertNotifier.EXTRA_OPEN_ALERTS, false) == true) {
            alertRequestId.intValue++
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
        val startedAt = SystemClock.elapsedRealtime()
        try {
            ledger = withContext(Dispatchers.IO) { LedgerProvider.get(context) }
            Log.i("LedgerStartup", "encrypted ledger opened in ${SystemClock.elapsedRealtime() - startedAt}ms")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("LedgerStartup", "encrypted ledger open failed after ${SystemClock.elapsedRealtime() - startedAt}ms", error)
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
    MarketStartupShell(errorMessage, onRetry)
}

@Composable
@ReadOnlyComposable
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
private fun MarketApp(ledger: Ledger, alertRequestId: Int = 0) {
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            DailyPortfolioSnapshotScheduler.ensureScheduled(appContext)
            AlertNotifier.ensureChannel(appContext)
            AlertEvaluationScheduler.ensureScheduled(appContext)
        }
    }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val fundCatalogCacheFile = LocalContext.current.applicationContext.cacheDir.resolve("fund-catalog-cache.js")
    val marketRepository = remember(ledger) { MarketRepositoryImpl(ledger, Dispatchers.IO) }
    val fundRepository = remember(ledger, fundCatalogCacheFile) {
        FundRepositoryImpl(ledger, Dispatchers.IO, fundCatalogCacheFile)
    }
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
    var selectedMarket by remember { mutableStateOf<String?>(if (alertRequestId > 0) ALERT_ROUTE else null) }
    var showSectors by remember { mutableStateOf(false) }
    LaunchedEffect(alertRequestId) {
        if (alertRequestId > 0) {
            tab = Tab.HOME
            selectedMarket = ALERT_ROUTE
            showSectors = false
        }
    }
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
    val portfolioRiskFeatureState = rememberPortfolioRiskFeatureState(context)
    val updateState = rememberUpdateFeatureState(scope, Dispatchers.IO)
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
    LaunchedEffect(ledger) {
        val updated = withContext(Dispatchers.IO) {
            runCatching { ledger.syncFundNavQuotes() }.getOrDefault(0)
        }
        if (updated > 0) revision++
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
        val failure = withContext(Dispatchers.IO) {
            runCatching { ledger.savePortfolioSnapshot(positions) }.exceptionOrNull()
        }
        if (failure != null) {
            dataMessage = "今日快照暂未保存：${failure.userFacingMessage("请稍后重试")}"
            dataMessageIsError = true
        }
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
    val weekFacts = remember(snapshotRevision, revision, today) {
        PortfolioAnalytics.facts(ReportPeriod.WEEK, today, portfolioSnapshots, positionSnapshots, trades, cashEvents)
    }
    val monthFacts = remember(snapshotRevision, revision, today) {
        PortfolioAnalytics.facts(ReportPeriod.MONTH, today, portfolioSnapshots, positionSnapshots, trades, cashEvents)
    }
    val assistantFacts = remember(revision, snapshotRevision, marketQuotes, positions, cashBalance) {
        AssistantFacts.build(
            reports = listOf(todayFacts, weekFacts, monthFacts),
            quotes = marketQuotes,
            positions = positions,
            cashBalance = cashBalance
        )
    }
    val assistantFactsState = rememberUpdatedState(assistantFacts)
    val assistantRegistry = remember(ledger, marketRepository) {
        ReadOnlyAgentTools.createRegistry(context, ledger, marketRepository, Dispatchers.IO, fundRepository)
    }
    val assistantState = rememberAssistantFeatureState(
        ledger = ledger,
        registry = assistantRegistry,
        scope = scope,
        ioDispatcher = Dispatchers.IO,
        offlineFacts = { assistantFactsState.value },
        riskFeatureState = portfolioRiskFeatureState,
        onPortfolioChanged = { revision++ }
    )
    val alertState = remember(ledger) {
        AlertFeatureState(ledger, scope) { event -> AlertNotifier.post(appContext, event) }
            .also { it.refresh() }
    }
    LaunchedEffect(marketQuotes, positions) {
        if (marketQuotes.isNotEmpty() || positions.isNotEmpty()) {
            alertState.checkNow(marketQuotes, positions)
        }
    }
    val ledgerDraft = remember { LedgerDraft() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerSections = remember {
        listOf(
            "行情" to listOf(
                DrawerEntry("总览", "home", Tab.HOME),
                DrawerEntry("黄金行情", "coin", Tab.HOME, route = GOLD_ROUTE),
                DrawerEntry("行业板块", "grid", Tab.HOME, sectors = true),
                DrawerEntry("自选市场", "star", Tab.WATCHLIST)
            ),
            "我的" to listOf(
                DrawerEntry("交易记录", "plus", Tab.RECORD),
                DrawerEntry("资产持仓", "assets", Tab.ASSETS),
                DrawerEntry("量化分析", "chart", Tab.ANALYSIS)
            ),
            "工具" to listOf(
                DrawerEntry("数据健康", "pulse", Tab.HOME, route = HEALTH_ROUTE),
                DrawerEntry("提醒", "bell", Tab.HOME, route = ALERT_ROUTE),
                DrawerEntry("AI 助手", "spark", Tab.HOME, route = ASSISTANT_ROUTE),
                DrawerEntry("外观", "sliders", Tab.HOME, route = APPEARANCE_ROUTE),
                DrawerEntry("关于与更新", "refresh", Tab.HOME, route = UPDATE_ROUTE)
            )
        )
    }
    // System back walks up the hierarchy — drawer, then sub-page, then tab — before leaving the app.
    BackHandler(enabled = drawerState.isOpen || selectedMarket != null || showSectors || tab != Tab.HOME) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            selectedMarket != null -> selectedMarket = null
            showSectors -> showSectors = false
            else -> tab = Tab.HOME
        }
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        scrimColor = MarketTheme.colors.scrim.copy(alpha = 0.42f),
        drawerContent = {
            val palette = MarketTheme.colors
            val geometry = MarketTheme.geometry
            ModalDrawerSheet(
                modifier = Modifier.widthIn(max = 320.dp),
                drawerShape = RoundedCornerShape(topEnd = geometry.panel, bottomEnd = geometry.panel),
                drawerContainerColor = palette.surface
            ) {
                Column(
                    Modifier.fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(horizontal = geometry.xl, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(geometry.xs)
                ) {
                    Text("养基助手", color = palette.ink, style = MarketType.title)
                    Text("行情 · 板块 · 本机账本", color = palette.muted, style = MarketType.caption)

                    // The drawer answers "how am I doing" before it asks "where do you want to go".
                    val drawerAccount = accountValue ?: totalValue
                    val drawerAmountHidden =
                        concealOverviewAmounts && (accountValue != null || positions.any { it.value != null })
                    Box(
                        Modifier.fillMaxWidth()
                            .padding(top = geometry.lg)
                            .clip(RoundedCornerShape(geometry.card))
                            .background(
                                Brush.linearGradient(
                                    listOf(palette.accent, palette.accent.shade(0.74f)),
                                    start = androidx.compose.ui.geometry.Offset.Zero,
                                    end = androidx.compose.ui.geometry.Offset(700f, 700f)
                                )
                            )
                            .padding(geometry.lg)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(geometry.sm)) {
                            Text(
                                if (cashBalance != null) "账户总资产" else "持仓市值 · 不含现金",
                                color = Color.White.copy(alpha = 0.75f), style = MarketType.label
                            )
                            Text(
                                if (drawerAmountHidden) "¥••••••" else "¥${drawerAccount.portfolioMoney()}",
                                color = Color.White,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.8).sp
                            )
                            val today = todayFacts.profit
                            Box(
                                Modifier.clip(RoundedCornerShape(geometry.chip))
                                    .background(Color.White.copy(alpha = 0.18f))
                                    .padding(horizontal = 9.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    when {
                                        drawerAmountHidden -> "今日 ••••"
                                        today == null -> "今日盈亏暂不可用"
                                        else -> "今日 ${if (today.signum() > 0) "+" else ""}¥${today.portfolioMoney()}"
                                    },
                                    color = Color.White, style = MarketType.numberSmall
                                )
                            }
                        }
                    }

                    drawerSections.forEach { (section, entries) ->
                        Text(
                            section,
                            color = palette.muted.copy(alpha = 0.9f),
                            style = MarketType.caption,
                            letterSpacing = 1.6.sp,
                            modifier = Modifier.padding(top = geometry.xl, bottom = geometry.xs)
                        )
                        entries.forEach { entry ->
                            val selected = tab == entry.tab && when {
                                entry.route != null -> selectedMarket == entry.route
                                entry.sectors -> showSectors && selectedMarket == null
                                else -> !showSectors && selectedMarket == null
                            }
                            val shape = RoundedCornerShape(geometry.chip)
                            Row(
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 50.dp)
                                    .clip(shape)
                                    .background(if (selected) palette.accentSoft else Color.Transparent)
                                    .marketPressable {
                                        tab = entry.tab
                                        showSectors = entry.sectors
                                        selectedMarket = entry.route
                                        scope.launch { drawerState.close() }
                                    }
                                    .padding(start = 10.dp, end = geometry.lg),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(geometry.lg)
                            ) {
                                MarketIcon(
                                    entry.icon,
                                    size = 22.dp,
                                    tint = if (selected) palette.accent else palette.muted
                                )
                                Text(
                                    entry.label,
                                    color = if (selected) palette.ink else palette.inkSoft,
                                    style = MarketType.nav,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f)
                                )
                                if (selected) {
                                    Text(
                                        "当前",
                                        color = palette.accent,
                                        style = MarketType.caption,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(geometry.chip))
                                            .background(palette.surface)
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                } else {
                                    MarketIcon("forward", size = 15.dp, tint = palette.line)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(geometry.xl))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line.copy(alpha = 0.7f)))
                    Spacer(Modifier.height(geometry.md))
                    Text("账本与偏好都保存在本机，不上传", color = palette.muted, style = MarketType.caption)
                    Text("行情可能存在延迟，仅供研究参考", color = palette.muted, style = MarketType.caption)
                    Spacer(Modifier.height(geometry.sm))
                    Text("从左边缘右滑，或点左上角按钮，可随时打开这里", color = palette.muted.copy(alpha = 0.85f), style = MarketType.caption)
                }
            }
        }
    ) {
      CompositionLocalProvider(
        LocalOpenDrawer provides { scope.launch { drawerState.open() } }
      ) {

      Box(Modifier.fillMaxSize()) {
      Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { insets ->        AnimatedContent(
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
                    onBack = { selectedMarket = null },
                    modifier = Modifier.padding(insets)
                ) else if (route.second == UPDATE_ROUTE) UpdateScreen(
                    state = updateState,
                    onBack = { selectedMarket = null },
                    modifier = Modifier.padding(insets)
                ) else if (route.second == APPEARANCE_ROUTE) AppearanceScreen(
                    onBack = { selectedMarket = null },
                    modifier = Modifier.padding(insets)
                ) else if (route.second == ALERT_ROUTE) AlertScreen(
                    state = alertState,
                    quotes = marketQuotes,
                    positions = positions,
                    onBack = { selectedMarket = null },
                    modifier = Modifier.padding(insets)
                ) else if (route.second == ASSISTANT_ROUTE) AssistantScreen(
                    state = assistantState,
                    onBack = { selectedMarket = null },
                    onOpenMarket = { secid -> selectedMarket = secid; showSectors = false; tab = Tab.WATCHLIST },
                    modifier = Modifier.padding(insets)
                ) else if (route.second == GOLD_ROUTE) GoldDashboardScreen(
                    quote = marketQuotes[MarketApi.GOLD_SPOT_ID],
                    domesticQuote = marketQuotes["GOLD.SGE.AU9999"],
                    internationalCnyGramQuote = marketQuotes[MarketApi.GOLD_CNY_GRAM_ID],
                    etfQuote = marketQuotes["1.518880"], marketRepository = marketRepository,
                    ledger = ledger,
                    goldPositions = positions.filter { it.asset.kind == AssetKind.GOLD },
                    message = goldMessage, quoteLoading = goldLoading, onRefresh = ::refreshGoldSpot,
                    onOpenEtf = { selectedMarket = "1.518880"; showSectors = false; tab = Tab.WATCHLIST },
                    onBack = { selectedMarket = null }, modifier = Modifier.padding(insets)
                ) else if (route.third) MarketBoardExplorerScreen(
                    snapshots = marketBoardState.snapshots,
                    loadingTypes = marketBoardState.loadingTypes,
                    message = marketBoardState.message,
                    marketRepository = marketRepository,
                    onRefresh = marketBoardState::refresh,
                    onBack = { showSectors = false },
                    onOpenInstrument = { secid ->
                        selectedMarket = secid
                        showSectors = false
                        tab = Tab.WATCHLIST
                    },
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
                        onToggle = { id, watched ->
                            runCatching { ledger.setWatched(id, watched) }
                                .onSuccess { revision++ }
                                .onFailure { marketMessage = it.userFacingMessage("自选保存失败") }
                        },
                        onOpen = { selectedMarket = it; showSectors = false }, modifier = Modifier.padding(insets)
                    ) else MarketDetailScreen(
                        secid, marketQuotes[secid], marketRepository,
                        onBack = { selectedMarket = null },
                        onToggle = { watched ->
                            runCatching { ledger.setWatched(secid, watched) }
                                .onSuccess { revision++ }
                                .onFailure { marketMessage = it.userFacingMessage("自选保存失败") }
                        },
                        isWatched = ledger.watchlist().contains(secid), modifier = Modifier.padding(insets)
                    )
                }
                Tab.RECORD -> RecordScreen(
                    ledger, fundRepository, marketRepository, marketQuotes, pending, cashBalance, cashEvents,
                    snackbarHostState, ledgerDraft, { revision++ }, Modifier.padding(insets)
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
                    onRecord = { tab = Tab.RECORD },
                    dataMessage = dataMessage, dataMessageIsError = dataMessageIsError,
                    modifier = Modifier.padding(insets)
                )
                Tab.ANALYSIS -> AnalysisScreen(
                    positions, trades, cashEvents, totalCost, holding, realized, portfolioSnapshots, positionSnapshots,
                    invalidatedSnapshotCount,
                    marketRepository = marketRepository,
                    riskFeatureState = portfolioRiskFeatureState,
                    onRecordSnapshot = {
                        scope.launch {
                            withContext(Dispatchers.IO) { ledger.savePortfolioSnapshot(positions) }
                            snapshotRevision++
                        }
                    },
                    onRebuildSnapshots = {
                        scope.launch {
                            val from = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).withDayOfMonth(1).minusDays(1)
                            withContext(Dispatchers.IO) { ledger.rebuildPortfolioSnapshots(from) }
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
        // The bar floats above the page so content keeps scrolling underneath the glass material.
        if (!imeVisible && selectedMarket != ASSISTANT_ROUTE) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                MarketNavBar(
                    items = Tab.entries.map { MarketNavItem(it.label, it.icon) },
                    selectedIndex = Tab.entries.indexOf(tab),
                    onSelect = { index ->
                        val item = Tab.entries[index]
                        tab = item
                        if (item != Tab.WATCHLIST) {
                            selectedMarket = null
                            showSectors = false
                        }
                    }
                )
            }
        }
      }
    }
    }
}

@Composable
internal fun NavigationGlyph(name: String, selected: Boolean, tint: Color = if (selected) Ink else Muted) =
    MarketIcon(name, size = 24.dp, tint = tint, filled = selected, onFilled = White)
@Composable
internal fun ScreenColumn(
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    verticalPadding: Dp = 14.dp,
    bottomClearance: Dp = MarketNavBarClearance,
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
    androidx.compose.runtime.CompositionLocalProvider(LocalMarketScrollOffset provides scrollState.value) {
    Box(modifier.fillMaxSize().nestedScroll(connection)) {
        Column(
            Modifier.fillMaxSize().graphicsLayer { translationY = animatedPull * 0.28f }
                .verticalScroll(scrollState)
                .padding(start = 20.dp, end = 20.dp, top = verticalPadding, bottom = verticalPadding + bottomClearance),
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

}

/**
 * Header for top-level tabs: the drawer trigger sits top-left, where root navigation belongs on both
 * iOS and Android, an optional status pill goes top-right, and the title follows below. Sub-pages pass
 * [onBack] and get their back affordance in the very same spot.
 */
/**
 * Header for top-level tabs: the drawer trigger sits beside the title (not above it), an optional
 * status pill goes on the right, and the subtitle carries the count. Sub-pages pass [onBack] and get
 * the back affordance in the very same slot.
 */
@Composable
internal fun BrandHeader(title: String, trailing: String = "", onBack: (() -> Unit)? = null) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    MarketLargeTitle(
        title = title,
        leading = if (onBack == null) {
            { MarketMenuButton() }
        } else {
            { MarketBackButton(label = "总览", onClick = onBack) }
        },
        trailing = if (trailing.isEmpty()) null else {
            {
                Box(
                    Modifier.clip(RoundedCornerShape(geometry.chip)).background(palette.surfaceAlt)
                        .padding(horizontal = geometry.lg, vertical = 6.dp)
                ) { Text(trailing, color = palette.muted, style = MarketType.label) }
            }
        }
    )
}

@Composable
internal fun GoldOverviewCard(domestic: MarketQuote?, international: MarketQuote?, etf: MarketQuote?, onOpenGold: () -> Unit) {
    Card(onClick = onOpenGold, modifier = Modifier.fillMaxWidth(), shape = TacticalPanel,
        colors = CardDefaults.cardColors(containerColor = White), border = BorderStroke(1.dp, Color(0xFFD8D8D2)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 5.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("国内 · Au99.99", color = Muted, fontSize = 11.sp)
                        AnimatedTicker(domestic?.let { "¥${String.format("%.2f", it.price)}" } ?: "—", Ink, 18.sp)
                        Text("人民币 / 克", color = Muted, fontSize = 11.sp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("国际现货 · XAU/USD", color = Muted, fontSize = 11.sp)
                        AnimatedTicker(international?.let { "US\$${String.format("%,.2f", it.price)}" } ?: "—", Ink, 17.sp)
                        Text("USD / troy oz · 美元 / 金衡盎司原始报价", color = Muted, fontSize = 11.sp, maxLines = 1)
                    }
                }
                Text("国内按元/克 · 国际按美元/盎司 · ETF ${etf?.changePct?.asSignedPercent() ?: "—"}", color = Muted, fontSize = 11.sp)
            }
            Text("进入行情  ›", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

internal fun Double.asSignedPercent() = "${if (this > 0) "+" else ""}${String.format("%.2f", this)}%"

internal fun MarketQuote.displaySource(): String = when {
    source.startsWith("gold-api") -> "gold-api.com"
    source == "sge-au9999" -> "上海黄金交易所"
    source == "tencent" -> "腾讯行情"
    else -> source
}

@Composable
internal fun AnimatedTicker(value: String, color: Color, fontSize: androidx.compose.ui.unit.TextUnit,
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
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    Card(
        Modifier.fillMaxWidth().marketShadow(RoundedCornerShape(geometry.card), MarketElevation.card),
        shape = RoundedCornerShape(geometry.card),
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        border = BorderStroke(1.dp, palette.line.copy(alpha = 0.85f))
    ) {
        Column(Modifier.padding(geometry.xl), verticalArrangement = Arrangement.spacedBy(geometry.sm)) {
            Text(title, color = palette.ink, style = MarketType.section)
            Text(body, color = palette.muted, style = MarketType.body)
        }
    }
}

@Composable
internal fun InfoCard(title: String, body: String, color: Color) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(geometry.card),
        colors = CardDefaults.cardColors(containerColor = palette.accentSoft)
    ) {
        Column(Modifier.padding(geometry.xl), verticalArrangement = Arrangement.spacedBy(geometry.sm)) {
            Text(title, color = color, style = MarketType.section)
            Text(body, color = palette.inkSoft, style = MarketType.body)
        }
    }
}

@Composable
internal fun fieldColors(): TextFieldColors {
    val palette = MarketTheme.colors
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = palette.ink, unfocusedTextColor = palette.ink,
        focusedContainerColor = palette.surface, unfocusedContainerColor = palette.surface,
        focusedBorderColor = palette.accent, unfocusedBorderColor = palette.line,
        focusedLabelColor = palette.accent, unfocusedLabelColor = palette.muted,
        focusedPlaceholderColor = palette.muted, unfocusedPlaceholderColor = palette.muted,
        cursorColor = palette.accent
    )
}
