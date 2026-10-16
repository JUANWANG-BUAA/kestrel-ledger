package cn.market.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.market.assistant.domain.market.CachedMarketChart
import cn.market.assistant.domain.market.ChartPeriod
import cn.market.assistant.domain.market.MarketBoard
import cn.market.assistant.domain.market.BoardMemberAnalytics
import cn.market.assistant.domain.market.MarketBoardSnapshot
import cn.market.assistant.domain.market.MarketBoardType
import cn.market.assistant.domain.market.MarketRepository
import cn.market.assistant.domain.quant.MarketBoardAnalytics
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.runtime.ReadOnlyComposable

private val BoardInk: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.ink
private val BoardMuted: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.muted
private val BoardPaper: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.paper
private val BoardWhite: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.surface
private val BoardAccent: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.accent
private val BoardUp: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.up
private val BoardDown: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.down
private val BoardLine: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.line

@Composable
internal fun MarketBoardTicker(
    snapshots: Map<MarketBoardType, MarketBoardSnapshot>,
    loadingTypes: Set<MarketBoardType>,
    message: String,
    onOpenBoards: () -> Unit,
    onRefreshBoards: () -> Unit
) {
    val boards = remember(snapshots) {
        snapshots.values.flatMap { it.boards }.sortedByDescending { it.changePct }
    }
    val industryCount = snapshots[MarketBoardType.INDUSTRY]?.boards?.size ?: 0
    val conceptCount = snapshots[MarketBoardType.CONCEPT]?.boards?.size ?: 0
    val listState = rememberLazyListState()
    val pixelsPerSecond = with(LocalDensity.current) { 18.dp.toPx() }

    LaunchedEffect(boards) {
        if (boards.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(0)
        var previousFrame = 0L
        while (isActive) {
            val frameTime = withFrameNanos { it }
            if (previousFrame != 0L) {
                val elapsedSeconds = (frameTime - previousFrame) / 1_000_000_000f
                listState.scrollBy(pixelsPerSecond * elapsedSeconds)
                val firstVisibleIndex = listState.firstVisibleItemIndex
                if (firstVisibleIndex >= boards.size) {
                    listState.scrollToItem(
                        firstVisibleIndex - boards.size,
                        listState.firstVisibleItemScrollOffset
                    )
                }
            }
            previousFrame = frameTime
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BoardWhite),
        border = BorderStroke(1.dp, BoardLine)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("主题板块", Modifier.weight(1f), color = BoardInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onOpenBoards) { Text("全量 →") }
                TextButton(onClick = onRefreshBoards, enabled = loadingTypes.isEmpty()) {
                    Text(if (loadingTypes.isNotEmpty()) "同步中" else "刷新")
                }
            }
            val latestSnapshot = snapshots.values.maxOfOrNull { it.fetchedAt }
            Text(
                when {
                    boards.isNotEmpty() -> "行业 $industryCount · 概念 $conceptCount · 全量 ${boards.size} 项动态轮播 · 快照 ${latestSnapshot?.boardTime() ?: "时间未知"}"
                    loadingTypes.isNotEmpty() -> "正在同步全市场板块数据…"
                    message.isNotBlank() -> message
                    else -> "进入全量板块页浏览，或点击刷新同步行业与概念数据"
                },
                color = BoardMuted,
                fontSize = 10.sp
            )
            if (boards.isNotEmpty() && (loadingTypes.isNotEmpty() || message.isNotBlank())) {
                Text(
                    if (loadingTypes.isNotEmpty()) "正在同步全市场板块数据…" else message,
                    color = BoardMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (boards.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(66.dp), contentAlignment = Alignment.CenterStart) {
                    Text(
                        if (loadingTypes.isNotEmpty()) "正在读取数百个行业和主题概念…"
                        else "点击进入全市场板块，点击刷新后加载完整列表。",
                        color = BoardMuted,
                        fontSize = 11.sp
                    )
                }
            } else {
                val tickerFade = MarketTheme.colors.surface
                Box(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(12.dp))) {
                    LazyRow(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(count = boards.size * 2, key = { it }) { index ->
                            BoardTickerCard(boards[index % boards.size])
                        }
                    }
                    Box(Modifier.align(Alignment.CenterStart).width(16.dp).fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(tickerFade, Color.Transparent))))
                    Box(Modifier.align(Alignment.CenterEnd).width(16.dp).fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, tickerFade))))
                }
            }
        }
    }
}

@Composable
private fun BoardTickerCard(board: MarketBoard) {
    val tickerPalette = MarketTheme.colors
    Card(
        modifier = Modifier.width(140.dp).height(76.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = tickerPalette.surfaceAlt),
        border = BorderStroke(1.dp, tickerPalette.line.copy(alpha = 0.85f))
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(board.name, Modifier.weight(1f), color = tickerPalette.ink, style = MarketType.label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(board.type.label, color = tickerPalette.muted, style = MarketType.caption)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(String.format("%.2f", board.price), Modifier.weight(1f), color = tickerPalette.ink, style = MarketType.number)
                Text(board.changePct.signedPct(), color = board.changePct.marketColor(), style = MarketType.label,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun MarketBoardExplorerScreen(
    snapshots: Map<MarketBoardType, MarketBoardSnapshot>,
    loadingTypes: Set<MarketBoardType>,
    message: String,
    marketRepository: MarketRepository,
    onRefresh: (MarketBoardType) -> Unit,
    onBack: () -> Unit,
    onOpenInstrument: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var type by remember { mutableStateOf(MarketBoardType.INDUSTRY) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(BoardSort.GAINERS) }
    var selectedBoard by remember { mutableStateOf<MarketBoard?>(null) }
    val snapshot = snapshots[type]
    val boards = snapshot?.boards.orEmpty()
    val filteredBoards = remember(boards, query, sort) {
        val matches = boards.filter { board ->
            query.isBlank() || board.name.contains(query.trim(), ignoreCase = true) ||
                board.id.substringAfter('.').contains(query.trim(), ignoreCase = true)
        }
        when (sort) {
            BoardSort.GAINERS -> matches.sortedByDescending { it.changePct }
            BoardSort.LAGGARDS -> matches.sortedBy { it.changePct }
            BoardSort.TURNOVER -> matches.sortedByDescending { it.turnoverAmount ?: Double.NEGATIVE_INFINITY }
            BoardSort.ADVANCERS -> matches.sortedByDescending { it.advancingCount - it.decliningCount }
        }
    }
    val breadth = remember(boards) { MarketBoardAnalytics.breadth(boards) }
    val listState = rememberLazyListState()
    val isLoading = type in loadingTypes
    val filterColors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
        selectedContainerColor = Color(0xFFF0E8DC),
        selectedLabelColor = BoardInk,
        containerColor = BoardWhite,
        labelColor = BoardMuted
    )
    LaunchedEffect(type, query, sort) { listState.scrollToItem(0) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
        contentPadding = PaddingValues(bottom = MarketNavBarClearance),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "board-controls") {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MarketBackButton(label = "总览", onClick = onBack)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onRefresh(type) }, enabled = !isLoading) {
                        Text(if (isLoading) "同步中" else "刷新 ${type.label}")
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("全市场板块", color = BoardInk, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
                        Text("行业指数 + 主题概念 · 真实市场宽度与成交数据", color = BoardMuted, style = MarketType.caption)
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(MarketTheme.geometry.chip))
                            .background(BoardAccent.copy(alpha = 0.12f))
                            .padding(horizontal = 11.dp, vertical = 6.dp)
                    ) {
                        Text("A 股", color = BoardAccent, style = MarketType.label, fontWeight = FontWeight.SemiBold)
                    }
                }

                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MarketBoardType.entries.forEach { option ->
                        val count = snapshots[option]?.boards?.size
                        MarketChoiceChip(
                            label = if (count == null) option.label else "${option.label} $count",
                            selected = type == option,
                            onClick = { type = option }
                        )
                    }
                }

                BoardBreadthCard(breadth, snapshot, isLoading)

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索板块名称或代码", style = MarketType.label) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BoardAccent,
                        unfocusedBorderColor = BoardLine,
                        focusedLabelColor = BoardAccent,
                        cursorColor = BoardAccent
                    )
                )

                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BoardSort.entries.forEach { option ->
                        MarketChoiceChip(
                            label = option.label,
                            selected = sort == option,
                            onClick = { sort = option }
                        )
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${filteredBoards.size} / ${snapshot?.expectedCount ?: 0} 个板块", Modifier.weight(1f),
                        color = BoardInk, style = MarketType.numberSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        snapshot?.fetchedAt?.let { "${snapshot.source.boardSourceLabel()} · ${it.boardTime()}" }
                            ?: if (isLoading) "正在下载完整列表" else "暂无本地快照",
                        color = BoardMuted,
                        style = MarketType.caption
                    )
                }
                if (message.isNotBlank()) Text(message, color = BoardMuted, style = MarketType.caption)
            }
        }

        if (filteredBoards.isEmpty() && !isLoading) {
            item(key = "board-empty") {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (snapshot == null) "正在准备全市场数据" else "没有匹配板块",
                            color = BoardInk, style = MarketType.title)
                        Text(if (snapshot == null) "首次同步完成后会缓存数百个行业与概念板块。" else "更换关键词，或刷新当前板块集合。",
                            color = BoardMuted, style = MarketType.body, textAlign = TextAlign.Center)
                    }
                }
            }
        } else {
            itemsIndexed(filteredBoards, key = { _, board -> board.id }) { index, board ->
                MarketBoardRow(index + 1, board, onClick = { selectedBoard = board })
            }
        }
    }

    selectedBoard?.let { board ->
        MarketBoardDetailSheet(
            board = board,
            repository = marketRepository,
            onOpenInstrument = onOpenInstrument,
            onDismiss = { selectedBoard = null }
        )
    }
}

private enum class BoardSort(val label: String) {
    GAINERS("涨幅靠前"),
    LAGGARDS("跌幅靠前"),
    TURNOVER("成交额高"),
    ADVANCERS("涨跌家数")
}

@Composable
private fun BoardBreadthCard(
    breadth: cn.market.assistant.domain.quant.MarketBoardBreadth,
    snapshot: MarketBoardSnapshot?,
    isLoading: Boolean
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = BoardWhite),
        border = BorderStroke(1.dp, BoardLine)
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("市场宽度", color = BoardInk, style = MarketType.section)
                    Text("按 ${snapshot?.type?.label ?: "当前板块"}指数涨跌统计，不重复汇总成分股", color = BoardMuted, style = MarketType.caption)
                }
                Text(
                    if (snapshot == null) if (isLoading) "同步中" else "等待数据"
                    else "${breadth.total} 个指数",
                    color = BoardAccent,
                    style = MarketType.label,
                    fontWeight = FontWeight.Medium
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                BoardMetric("上涨", snapshot?.let { "${breadth.advancing}" } ?: "—", BoardUp, Modifier.weight(1f))
                BoardMetric("下跌", snapshot?.let { "${breadth.declining}" } ?: "—", BoardDown, Modifier.weight(1f))
                BoardMetric("上涨占比", snapshot?.let { "${String.format("%.1f", breadth.advanceRatioPct)}%" } ?: "—",
                    BoardInk, Modifier.weight(1f))
                BoardMetric("中位涨幅", snapshot?.let { breadth.medianChangePct.signedPct() } ?: "—",
                    snapshot?.let { breadth.medianChangePct.marketColor() } ?: BoardMuted, Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("板块宽度指数", color = BoardMuted, style = MarketType.caption)
                Spacer(Modifier.weight(1f))
                Text(
                    snapshot?.let { "${if (breadth.breadthIndex > 0) "+" else ""}${String.format("%.1f", breadth.breadthIndex)}" } ?: "—",
                    color = if (snapshot == null) BoardMuted else breadth.breadthIndex.marketColor(),
                    style = MarketType.numberSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    snapshot?.let { " · 均值 ${breadth.averageChangePct.signedPct()} · 平盘 ${breadth.unchanged}" } ?: "",
                    color = BoardMuted,
                    style = MarketType.caption
                )
            }
        }
    }
}

@Composable
private fun BoardMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = BoardMuted, style = MarketType.caption, maxLines = 1)
        Text(value, color = color, style = MarketType.numberLarge, maxLines = 1)
    }
}

@Composable
private fun MarketBoardRow(rank: Int, board: MarketBoard, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = BoardWhite),
        border = BorderStroke(1.dp, BoardLine),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 4.dp)
    ) {
        val geometry = MarketTheme.geometry
        Column(
            Modifier.fillMaxWidth().padding(horizontal = geometry.lg, vertical = geometry.md),
            verticalArrangement = Arrangement.spacedBy(geometry.md)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rank.toString().padStart(2, '0'),
                    Modifier.width(30.dp),
                    color = if (rank <= 3) BoardAccent else BoardMuted,
                    style = MarketType.numberSmall
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(board.name, color = BoardInk, style = MarketType.number,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${board.type.label} · ${board.id.substringAfter('.')}", color = BoardMuted, style = MarketType.caption)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(String.format("%.2f", board.price), color = BoardInk, style = MarketType.numberLarge)
                    val changeColor = board.changePct.marketColor()
                    Box(
                        Modifier.clip(RoundedCornerShape(geometry.chip))
                            .background(changeColor.copy(alpha = 0.10f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(board.changePct.signedPct(), color = changeColor, style = MarketType.numberSmall)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(geometry.md)) {
                BoardDetailValue("涨 / 跌家", "${board.advancingCount} / ${board.decliningCount}", Modifier.weight(1f))
                BoardDetailValue("成交额", board.turnoverAmount?.let(::formatYuan) ?: "—", Modifier.weight(1f))
                BoardDetailValue("换手率", board.turnoverRatePct?.let { "${String.format("%.2f", it)}%" } ?: "—", Modifier.weight(1f))
                BoardDetailValue("总市值", board.marketCap?.let(::formatYuan) ?: "—", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BoardDetailValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = BoardMuted, style = MarketType.caption, maxLines = 1)
        Text(value, color = BoardInk, style = MarketType.numberSmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarketBoardDetailSheet(
    board: MarketBoard,
    repository: MarketRepository,
    onOpenInstrument: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var period by remember(board.id) { mutableStateOf(ChartPeriod.DAY) }
    var chart by remember(board.id, period) { mutableStateOf<CachedMarketChart?>(null) }
    var chartMessage by remember(board.id, period) { mutableStateOf("读取历史走势…") }
    var loading by remember(board.id, period) { mutableStateOf(false) }
    var members by remember(board.id) { mutableStateOf<cn.market.assistant.domain.market.MarketBoardMemberSnapshot?>(null) }
    var membersMessage by remember(board.id) { mutableStateOf("") }
    var membersLoading by remember(board.id) { mutableStateOf(false) }
    var memberSort by remember(board.id) { mutableStateOf(BoardMemberAnalytics.MemberSort.CHANGE_DESC) }
    val scope = rememberCoroutineScope()

    fun loadMembers(force: Boolean) {
        if (membersLoading) return
        scope.launch {
            membersLoading = true
            val cached = runCatching { repository.cachedBoardMembers(board.id) }.getOrNull()
            cached?.takeIf { it.members.isNotEmpty() }?.let {
                members = it
                membersMessage = ""
            }
            val fresh = cached?.fetchedAt?.let { System.currentTimeMillis() - it * 1_000L < 15 * 60 * 1_000L } == true
            if (!force && fresh && cached?.members?.isNotEmpty() == true) {
                membersLoading = false
                return@launch
            }
            runCatching { repository.refreshBoardMembers(board.id, board.name) }
                .onSuccess { loaded ->
                    members = loaded
                    membersMessage = if (loaded.isTruncated) {
                        "公开接口按市值返回前 ${loaded.members.size} 只，板块共 ${loaded.totalCount} 只"
                    } else ""
                }
                .onFailure {
                    if (members?.members.isNullOrEmpty()) membersMessage = it.userFacingMessage("成分股暂不可用")
                }
            membersLoading = false
        }
    }

    LaunchedEffect(board.id) { loadMembers(force = false) }

    fun loadChart(force: Boolean) {
        if (loading) return
        scope.launch {
            loading = true
            val cached = runCatching { repository.cachedChart(board.id, period) }.getOrNull()
            cached?.takeIf { it.bars.isNotEmpty() }?.let {
                chart = it
                chartMessage = ""
            }
            val fresh = cached?.cachedAt?.let { System.currentTimeMillis() - it < 12 * 60 * 60 * 1_000L } == true
            if (!force && fresh && (cached?.bars?.size ?: 0) >= 3) {
                loading = false
                return@launch
            }
            runCatching { repository.refreshChart(board.id, period) }
                .onSuccess { refreshed ->
                    if (refreshed.bars.isNotEmpty()) {
                        chart = refreshed
                        chartMessage = ""
                    } else if (chart?.bars.isNullOrEmpty()) {
                        chartMessage = "公开行情源暂未返回该板块的历史 K 线。"
                    }
                }
                .onFailure { if (chart?.bars.isNullOrEmpty()) chartMessage = it.userFacingMessage("历史走势暂不可用。") }
            loading = false
        }
    }

    LaunchedEffect(board.id, period) { loadChart(force = false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = BoardPaper) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(board.name, color = BoardInk, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("${board.type.label}板块 · ${board.id.substringAfter('.')}", color = BoardMuted, fontSize = 10.sp)
                }
                TextButton(onClick = { loadChart(force = true) }, enabled = !loading) {
                    Text(if (loading) "更新中" else "刷新走势", color = BoardAccent)
                }
            }

            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BoardWhite), border = BorderStroke(1.dp, BoardLine)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(String.format("%.2f", board.price), Modifier.weight(1f), color = BoardInk, fontSize = 29.sp,
                            fontWeight = FontWeight.SemiBold)
                        Text(board.changePct.signedPct(), color = board.changePct.marketColor(), fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BoardMetric("涨跌额", String.format("%.2f", board.change), board.change.marketColor(), Modifier.weight(1f))
                        BoardMetric("涨 / 跌家", "${board.advancingCount} / ${board.decliningCount}", BoardInk, Modifier.weight(1f))
                        BoardMetric("换手率", board.turnoverRatePct?.let { "${String.format("%.2f", it)}%" } ?: "—", BoardInk, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BoardMetric("成交额", board.turnoverAmount?.let(::formatYuan) ?: "—", BoardInk, Modifier.weight(1f))
                        BoardMetric("总市值", board.marketCap?.let(::formatYuan) ?: "—", BoardInk, Modifier.weight(1f))
                        BoardMetric("上涨家数占比", board.constituentAdvancePct(), BoardInk, Modifier.weight(1f))
                    }
                }
            }

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(ChartPeriod.DAY, ChartPeriod.WEEK, ChartPeriod.MONTH).forEach { option ->
                    FilterChip(
                        selected = period == option,
                        onClick = { period = option },
                        label = { Text(option.label, fontSize = 10.sp) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFF0E8DC),
                            selectedLabelColor = BoardInk,
                            containerColor = BoardWhite,
                            labelColor = BoardMuted
                        )
                    )
                }
            }

            BoardHistoryChart(chart?.bars?.map { it.close }.orEmpty(), Modifier.fillMaxWidth().height(142.dp))
            Text(
                chart?.takeIf { it.bars.isNotEmpty() }?.let { data ->
                    "${data.bars.first().day} — ${data.bars.last().day} · ${data.bars.size} 根 · 东方财富历史数据"
                } ?: chartMessage,
                color = BoardMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BoardWhite), border = BorderStroke(1.dp, BoardLine)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("成分股", color = BoardInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                members?.let { snapshot ->
                                    val (up, down) = BoardMemberAnalytics.advanceDecline(snapshot.members)
                                    val concentration = BoardMemberAnalytics.concentrationTopN(snapshot.members, 5)
                                    "已取 ${snapshot.members.size} 只 · 涨 $up / 跌 $down" +
                                        (concentration?.let { " · 前五市值占比 ${String.format("%.1f", it)}%" } ?: "")
                                } ?: if (membersMessage.isBlank()) "读取成分股…" else membersMessage,
                                color = BoardMuted, fontSize = 9.sp, lineHeight = 13.sp
                            )
                        }
                        TextButton(onClick = { loadMembers(force = true) }, enabled = !membersLoading) {
                            Text(if (membersLoading) "更新中" else "刷新成分", color = BoardAccent, fontSize = 11.sp)
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BoardMemberAnalytics.MemberSort.entries.forEach { option ->
                            FilterChip(
                                selected = memberSort == option,
                                onClick = { memberSort = option },
                                label = { Text(option.label, fontSize = 10.sp) },
                                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFF0E8DC),
                                    selectedLabelColor = BoardInk,
                                    containerColor = BoardWhite,
                                    labelColor = BoardMuted
                                )
                            )
                        }
                    }
                    val visibleMembers = members?.let { BoardMemberAnalytics.sortMembers(it.members, memberSort) }.orEmpty()
                    if (visibleMembers.isEmpty()) {
                        Text(membersMessage.ifBlank { "暂无成分股缓存，点击刷新成分" }, color = BoardMuted, style = MarketType.caption)
                    } else {
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            visibleMembers.forEach { member ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { onOpenInstrument(member.secid) }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(member.name, color = BoardInk, style = MarketType.numberSmall)
                                        Text(
                                            "${member.code} · ${member.marketCap?.let(::formatYuan) ?: "市值—"}",
                                            color = BoardMuted, style = MarketType.caption
                                        )
                                    }
                                    Text(
                                        String.format("%.2f", member.price),
                                        color = BoardInk, style = MarketType.number
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        member.changePct.signedPct(),
                                        color = member.changePct.marketColor(),
                                        style = MarketType.numberSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                        if (membersMessage.isNotBlank()) {
                            Text(membersMessage, color = BoardMuted, style = MarketType.caption)
                        }
                        Text(
                            "成分股为板块公开页面按市值排序的前若干只，不等同完整成分；行情来源 " +
                                "${members?.source ?: "—"} · 时点 ${members?.fetchedAt?.boardTime() ?: "—"}",
                            color = BoardMuted, style = MarketType.caption
                        )
                    }
                }
            }
            Text(
                "行情来源：${board.source.boardSourceLabel()} · 报价时点 ${board.quoteTime.boardTime()} · 列表快照 ${board.fetchedAt.boardTime()}",
                color = BoardMuted,
                style = MarketType.caption
            )
        }
    }
}

@Composable
private fun BoardHistoryChart(values: List<Double>, modifier: Modifier = Modifier) {
    val lineColor = values.lastOrNull()?.let { last ->
        if (values.firstOrNull()?.let { last >= it } == true) BoardUp else BoardDown
    } ?: BoardAccent
    Card(modifier, shape = RoundedCornerShape(15.dp), colors = CardDefaults.cardColors(containerColor = BoardWhite),
        border = BorderStroke(1.dp, BoardLine)) {
        if (values.size < 2) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("等待历史数据", color = BoardMuted, fontSize = 11.sp)
            }
        } else {
            Canvas(Modifier.fillMaxSize().padding(14.dp)) {
                val min = values.minOrNull() ?: return@Canvas
                val range = ((values.maxOrNull() ?: min) - min).coerceAtLeast(0.000001)
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = size.width * index / values.lastIndex.coerceAtLeast(1)
                    val y = size.height - ((value - min) / range).toFloat() * size.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                val baseline = size.height - ((values.first() - min) / range).toFloat() * size.height
                drawLine(Color(0xFFDEDDD7), Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
            }
        }
    }
}

private fun MarketBoard.constituentAdvancePct(): String {
    val changedCount = advancingCount + decliningCount
    return if (changedCount == 0) "—" else "${String.format("%.1f", advancingCount * 100.0 / changedCount)}%"
}

private fun formatYuan(value: Double): String = when {
    value >= 100_000_000_000 -> "${String.format("%.2f", value / 100_000_000_000)} 万亿"
    value >= 100_000_000 -> "${String.format("%.1f", value / 100_000_000)} 亿"
    else -> "${String.format("%.0f", value / 10_000)} 万"
}

private fun Double.signedPct(): String = "${if (this > 0) "+" else ""}${String.format("%.2f", this)}%"

@Composable
private fun Double.marketColor(): Color = when {
    this > 0 -> BoardUp
    this < 0 -> BoardDown
    else -> BoardMuted
}

private fun String.boardSourceLabel(): String = if (startsWith("eastmoney")) "东方财富" else this

private fun Long.boardTime(): String = runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"))
        .format(Instant.ofEpochSecond(this))
}.getOrDefault("时间未知")
