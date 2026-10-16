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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DailyPortfolioSnapshotScheduler.ensureScheduled(applicationContext)
        AlertNotifier.ensureChannel(applicationContext)
        AlertEvaluationScheduler.ensureScheduled(applicationContext)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.statusBarColor = android.graphics.Color.rgb(242, 241, 237)
        window.navigationBarColor = android.graphics.Color.rgb(28, 34, 37)
        setContent {
            val themeStore = rememberMarketThemeStore(LocalContext.current)
            val themeState = remember(themeStore) { themeStore.load() }
            LaunchedEffect(
                themeState.accent, themeState.mode, themeState.density, themeState.fontScale,
                themeState.corner, themeState.blurEnabled
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
                MaterialTheme(colorScheme = lightColorScheme(
                    primary = colors.accent, onPrimary = Color.White, secondary = colors.accent,
                    background = colors.paper, surface = colors.surface, onSurface = colors.ink,
                    onBackground = colors.ink, error = colors.up,
                    secondaryContainer = colors.accentSoft, onSecondaryContainer = colors.ink,
                    surfaceVariant = colors.surfaceAlt, outline = colors.line
                )) {
                    LedgerStartup { ledger -> MarketApp(ledger) }
                }
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
        ReadOnlyAgentTools.createRegistry(ledger, marketRepository, Dispatchers.IO)
    }
    val assistantState = rememberAssistantFeatureState(
        ledger = ledger,
        registry = assistantRegistry,
        scope = scope,
        ioDispatcher = Dispatchers.IO,
        offlineFacts = { assistantFactsState.value }
    )
    val appContext = LocalContext.current.applicationContext
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
                DrawerEntry("外观", "sliders", Tab.HOME, route = APPEARANCE_ROUTE)
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
                            .background(palette.surfaceAlt)
                            .padding(geometry.lg)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(geometry.sm)) {
                            Text(
                                if (cashBalance != null) "账户总资产" else "持仓市值 · 不含现金",
                                color = palette.muted, style = MarketType.label
                            )
                            Text(
                                if (drawerAmountHidden) "¥••••••" else "¥${drawerAccount.portfolioMoney()}",
                                color = palette.ink, style = MarketType.numberLarge
                            )
                            val today = todayFacts.profit
                            val todayColor = if (today != null) plColor(today) else palette.muted
                            Text(
                                when {
                                    drawerAmountHidden -> "今日 ••••"
                                    today == null -> "今日盈亏暂不可用"
                                    else -> "今日 ${if (today.signum() > 0) "+" else ""}¥${today.portfolioMoney()}"
                                },
                                color = todayColor, style = MarketType.numberSmall
                            )
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
      val pageBackdrop = rememberGraphicsLayer()
      CompositionLocalProvider(
        LocalOpenDrawer provides { scope.launch { drawerState.open() } },
        LocalMarketBackdrop provides if (MarketTheme.state?.blurEnabled != false) pageBackdrop else null
      ) {

      Box(Modifier.fillMaxSize()) {
      Box(
        Modifier.fillMaxSize().drawWithContent {
            pageBackdrop.record(
                density = this,
                layoutDirection = layoutDirection,
                size = IntSize(size.width.roundToInt(), size.height.roundToInt())
            ) { this@drawWithContent.drawContent() }
            drawLayer(pageBackdrop)
        }
      ) {
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
        // The bar floats above the page so content keeps scrolling underneath the glass material.
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
            MarketBackButton(label = "总览", onClick = onBack)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onRefresh(); refreshRevision++ }, enabled = !loading) { Text("刷新行情") }
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
        Card(Modifier.fillMaxWidth(), shape = TacticalPanel, colors = CardDefaults.cardColors(containerColor = White),
            border = BorderStroke(1.dp, Color(0xFFD8D8D2))) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("黄金 ETF K 线", color = Ink, style = MarketType.section, modifier = Modifier.weight(1f))
                    Text(
                        if (loading) "加载中" else "更新",
                        color = if (loading) MarketTheme.colors.muted else MarketTheme.colors.accent,
                        style = MarketType.label,
                        modifier = Modifier.marketPressable(enabled = !loading) { loadChart(true) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
                Text(
                    "518880 作为国内黄金交易代理，价格不等同于现货克价",
                    color = MarketTheme.colors.muted, style = MarketType.caption
                )
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
            colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("查看 518880 完整行情与指标") }
        if (message.isNotBlank()) Text(message, color = Muted, fontSize = 11.sp)
        Text("国际金价直接显示 XAU/USD 美元 / 金衡盎司报价。国内溢价比较会另按汇率和金衡盎司重量折算为人民币 / 克；ETF 图表仅观察国内交易品种走势。行情可能延迟。", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
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
        MarketIcon(
            if (hidden) "eyeOff" else "eye",
            size = 19.dp,
            tint = Color.White,
            modifier = Modifier.graphicsLayer { rotationZ = rotation }
        )
    }
}

@Composable
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
        MarketLargeTitle("资产", subtitle = "账户 · 持仓 · 行情", leading = { MarketMenuButton() })
        OverviewHeroCard(
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
            MarketQuickLink("全部持仓", "份额 · 成本 · 盈亏", Modifier.weight(1f), onClick = onOpenAssets)
            MarketQuickLink("黄金行情", "国内外金价 · 折算", Modifier.weight(1f), onClick = onOpenGold)
            MarketQuickLink("市场行情", "指数 · 板块 · 自选", Modifier.weight(1f), onClick = onOpenWatchlist)
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
        MarketSectionHeader("我的持仓") {
            Text("全部资产 ›", color = Muted, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onOpenAssets))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MarketCompactStat(
                "基金持仓",
                "${positions.count { it.asset.kind == AssetKind.FUND }} 只",
                Modifier.weight(1f)
            )
            MarketCompactStat(
                "黄金持仓",
                "${positions.count { it.asset.kind == AssetKind.GOLD }} 项",
                Modifier.weight(1f)
            )
        }
        if (positions.isEmpty()) {
            MarketCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("还没有持仓记录", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("记一笔后这里显示份额、成本与盈亏", color = Muted, fontSize = 10.sp)
                    }
                    Text(
                        "去记账",
                        color = Color.White, fontSize = 11.5.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Blue)
                            .clickable(onClick = onRecord)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        } else {
            positions.take(5).forEach { PositionCard(it, concealAmounts) }
        }
        if (pendingCount > 0) InfoCard("有 $pendingCount 笔交易待确认", "净值公布后补录确认净值，即可计入持仓。", Blue)
        if (positions.isNotEmpty()) {
            TextButton(onClick = onRecord, modifier = Modifier.fillMaxWidth()) { Text("记下买入或卖出") }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("行情观察", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                val latest = marketQuotes.values.maxOfOrNull { it.fetchedAt }
                Text(
                    if (latest == null) "行情暂未更新" else "行情截至 ${java.time.Instant.ofEpochSecond(latest).atZone(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))}",
                    color = Muted, fontSize = 10.sp
                )
            }
            Text(if (marketLoading) "刷新中" else "刷新", color = Muted, fontSize = 11.sp,
                modifier = Modifier.clickable(enabled = !marketLoading, onClick = onRefreshMarket))
            Spacer(Modifier.width(14.dp))
            Text("板块 ›", color = Muted, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onOpenSectors))
            Spacer(Modifier.width(14.dp))
            Text("自选 ›", color = Muted, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onOpenWatchlist))
        }
        GoldOverviewCard(marketQuotes["GOLD.SGE.AU9999"], marketQuotes[MarketApi.GOLD_SPOT_ID], marketQuotes["1.518880"], onOpenGold = onOpenGold)
        MarketBoardTicker(boardSnapshots, boardLoadingTypes, boardMessage, onOpenSectors, onRefreshBoards)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("全球大盘指数", Modifier.weight(1f), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        if (marketMessage.isNotBlank()) Text(marketMessage, color = Muted, fontSize = 11.sp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MarketInstrumentCatalog.overview.chunked(3).forEach { trio ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    trio.forEach { instrument ->
                        val quote = marketQuotes[instrument.secid]
                        MarketIndexTile(
                            name = instrument.name,
                            fallbackLabel = when {
                                quote == null -> instrument.market
                                quote.source == "daily-close" -> "近收"
                                else -> instrument.market
                            },
                            price = quote?.price?.let { String.format("%.2f", it) } ?: "—",
                            changePct = quote?.let { it.changePct.asSignedPercent() } ?: "暂无",
                            changeColor = quote?.let { plColor(BigDecimal.valueOf(it.changePct)) } ?: Muted,
                            modifier = Modifier.weight(1f),
                            onClick = { onOpenMarket(instrument.secid) }
                        )
                    }
                    repeat(3 - trio.size) { Spacer(Modifier.weight(1f)) }
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
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Ink, unfocusedTextColor = Ink,
    focusedContainerColor = White, unfocusedContainerColor = White,
    focusedBorderColor = Blue, unfocusedBorderColor = Color(0xFFD8D8D2),
    focusedLabelColor = Ink, unfocusedLabelColor = Muted, cursorColor = Ink
)
