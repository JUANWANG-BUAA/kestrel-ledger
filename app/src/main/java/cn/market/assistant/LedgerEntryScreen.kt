package cn.market.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.market.assistant.domain.fund.FundListedPriceAnalytics
import cn.market.assistant.domain.fund.FundRepository
import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.domain.market.MarketRepository
import cn.market.assistant.domain.portfolio.AccountCashBalance
import cn.market.assistant.domain.portfolio.AccountCashEvent
import cn.market.assistant.domain.portfolio.AccountCashEventType
import cn.market.assistant.domain.portfolio.AssetInfo
import cn.market.assistant.domain.portfolio.AssetKind
import cn.market.assistant.domain.portfolio.Trade
import cn.market.assistant.domain.portfolio.TradeSide
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Draft of the trade being entered. It is remembered above the tab host, so switching tabs, opening
 * the drawer or pressing back no longer throws away a half-filled form.
 */
@Stable
internal class LedgerDraft {
    val filter = mutableStateOf(AssetKind.FUND)
    val selected = mutableStateOf<AssetInfo?>(null)
    val transferTarget = mutableStateOf<AssetInfo?>(null)
    val confirmTarget = mutableStateOf<Trade?>(null)
    val search = mutableStateOf("")
    val side = mutableStateOf(TradeSide.BUY)
    val amount = mutableStateOf("")
    val units = mutableStateOf("")
    val price = mutableStateOf("")
    val fee = mutableStateOf("0")
    val shareFactor = mutableStateOf("")
    val transferSearch = mutableStateOf("")
    val transferUnits = mutableStateOf("")
    val transferCost = mutableStateOf("")
    val showFee = mutableStateOf(false)
    val note = mutableStateOf("")
    val message = mutableStateOf("")
    val messageIsError = mutableStateOf(false)
    val customCode = mutableStateOf("")
    val customName = mutableStateOf("")
    val confirmPrice = mutableStateOf("")
    val cashSetupOpen = mutableStateOf(false)
    val cashAmount = mutableStateOf("")
    val cashMemo = mutableStateOf("")
    val cashEventType = mutableStateOf(AccountCashEventType.DEPOSIT)
}

@Composable
internal fun RecordScreen(
    ledger: Ledger,
    fundRepository: FundRepository,
    marketRepository: MarketRepository,
    marketQuotes: Map<String, MarketQuote>,
    pending: List<Trade>,
    cashBalance: AccountCashBalance?,
    accountCashEvents: List<AccountCashEvent>,
    snackbarHostState: SnackbarHostState,
    draft: LedgerDraft,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val fundFeatureState = rememberFundFeatureState(fundRepository, marketRepository)
    val viewedFundProfile = fundFeatureState.profile
    val trackingIndexQuote = viewedFundProfile?.trackingIndexSecid?.let(marketQuotes::get)
    val canRefreshFundEstimate = viewedFundProfile?.let { profile ->
        val type = profile.fundType.orEmpty()
        val indexLinked = type.contains("指数型") || profile.name.contains("ETF联接")
        val hasLinkedEtfBenchmark = indexLinked && profile.trackingIndexSecid == null &&
            FundListedPriceAnalytics.marketSecid(profile.linkedEtfCode.orEmpty()) != null
        val hasHoldingsRoute = !type.contains("QDII", ignoreCase = true) &&
            (type.contains("股票") || type.contains("混合")) && profile.holdings.isNotEmpty()
        (indexLinked && (profile.trackingIndexSecid != null || hasLinkedEtfBenchmark)) || hasHoldingsRoute
    } == true
    LaunchedEffect(
        viewedFundProfile?.code,
        viewedFundProfile?.navDate,
        trackingIndexQuote?.fetchedAt,
        trackingIndexQuote?.changePct
    ) {
        viewedFundProfile?.let { fundFeatureState.estimateNav(it, trackingIndexQuote) }
    }
    LaunchedEffect(viewedFundProfile?.code, viewedFundProfile?.navDate) {
        viewedFundProfile?.let { fundFeatureState.loadListedMarketPrice(it) }
    }
    var filter by draft.filter
    var search by draft.search
    var selected by draft.selected
    var side by draft.side
    var amount by draft.amount
    var units by draft.units
    var price by draft.price
    var fee by draft.fee
    var shareFactor by draft.shareFactor
    var transferSearch by draft.transferSearch
    var transferTarget by draft.transferTarget
    var transferUnits by draft.transferUnits
    var transferCost by draft.transferCost
    var showFee by draft.showFee
    var note by draft.note
    var message by draft.message
    var messageIsError by draft.messageIsError
    var customCode by draft.customCode
    var customName by draft.customName
    var confirmTarget by draft.confirmTarget
    var confirmPrice by draft.confirmPrice
    var cashSetupOpen by draft.cashSetupOpen
    var cashAmount by draft.cashAmount
    var cashMemo by draft.cashMemo
    var cashEventType by draft.cashEventType
    var fundDetailOpen by remember { mutableStateOf(false) }
    LaunchedEffect(fundFeatureState.profileTarget?.code) {
        if (fundFeatureState.profileTarget == null) fundDetailOpen = false
    }
    val recentCashEvents = accountCashEvents.filter {
        it.type == AccountCashEventType.DEPOSIT || it.type == AccountCashEventType.WITHDRAWAL ||
            it.type == AccountCashEventType.DIVIDEND
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
        } else {
            val standardAu9999 = assets.firstOrNull { it.symbol == "GOLD.SGE.AU9999" }
                ?: AssetInfo("GOLD.SGE.AU9999", "上金所 Au99.99", AssetKind.GOLD, "黄金")
            assets.filterNot { it.symbol == standardAu9999.symbol }.take(2) + standardAu9999
        }
    }

    ScreenColumn(modifier, spacing = 11.dp, verticalPadding = 10.dp) {
        BrandHeader("记一笔")
        Text(
            when (side) {
                TradeSide.BUY -> "记录新买入，份额按确认净值计算。"
                TradeSide.SELL -> "记录卖出或赎回，不能超过当前持有份额。"
                TradeSide.OPENING_POSITION -> "录入已有持仓的份额和历史成本，从今天开始建立快照。"
                TradeSide.CASH_DIVIDEND -> if (cashBalance == null) {
                    "记录现金分红和代扣税费；税后金额计入已实现收益，启用现金跟踪后也会计入现金。"
                } else {
                    "记录现金分红和代扣税费；税后金额计入现金与已实现收益。"
                }
                TradeSide.DIVIDEND_REINVESTMENT -> "记录税后分红换得的新份额；新增份额和分红收益合并核算，不增加账户现金。"
                TradeSide.SHARE_SPLIT -> "按拆分比例调整持有份额，保留总成本并重算单位成本。"
                TradeSide.ASSET_TRANSFER -> "将份额和加权成本从一个已跟踪标的转到另一个；不产生买卖现金流。"
            },
            color = Muted, fontSize = 13.sp, lineHeight = 20.sp
        )

        val cashPalette = MarketTheme.colors
        val cashGeometry = MarketTheme.geometry
        Card(
            Modifier.fillMaxWidth().marketShadow(RoundedCornerShape(cashGeometry.card), MarketElevation.card),
            shape = RoundedCornerShape(cashGeometry.card),
            colors = CardDefaults.cardColors(containerColor = cashPalette.surface),
            border = BorderStroke(1.dp, cashPalette.line.copy(alpha = 0.85f))
        ) {
            Column(Modifier.padding(cashGeometry.xl), verticalArrangement = Arrangement.spacedBy(cashGeometry.md)) {
                Row(
                    Modifier.fillMaxWidth()
                        .then(
                            if (cashBalance == null) Modifier.marketPressable { cashSetupOpen = !cashSetupOpen }
                            else Modifier
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("账户现金", color = cashPalette.ink, style = MarketType.section)
                        Text(
                            when {
                                cashBalance != null -> "现金跟踪起点 ${cashBalance.trackingStart}"
                                cashSetupOpen -> "从今天起记录入金、出金和买卖对现金的影响"
                                else -> "未启用 · 启用后计入账户总资产"
                            },
                            color = cashPalette.muted, style = MarketType.caption
                        )
                    }
                    Text(
                        cashBalance?.amount?.let { "¥${it.portfolioMoney()}" } ?: "未启用",
                        color = cashPalette.ink, style = MarketType.numberLarge
                    )
                    if (cashBalance == null) {
                        Spacer(Modifier.width(cashGeometry.md))
                        Text(
                            if (cashSetupOpen) "收起 ›" else "启用 ›",
                            color = cashPalette.accent, style = MarketType.label
                        )
                    }
                }
                if (cashBalance == null && cashSetupOpen) {
                    NumericField(cashAmount, { cashAmount = it }, "从今天开始的期初可用现金（元）", Modifier.fillMaxWidth())
                    Text("既有基金和黄金持仓继续保留；历史买卖不会改写。从起点后的新买卖和现金分红会自动记入现金。", color = cashPalette.muted, style = MarketType.caption)
                    Button(onClick = {
                        runCatching { ledger.initializeAccountCash(cashAmount.toBigDecimalOrNull() ?: error("请填写期初现金")) }
                            .onSuccess { cashAmount = ""; message = "现金跟踪已启用"; messageIsError = false; onChanged() }
                            .onFailure { message = it.message ?: "设置失败"; messageIsError = true }
                    }, enabled = cashAmount.isNotBlank(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("开始跟踪现金") }
                }
                // Cash movements only make sense once tracking has a starting point.
                if (cashBalance != null) {
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
                            colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text(if (cashEventType == AccountCashEventType.DEPOSIT) "记入金" else "记出金") }
                    }
                    OutlinedTextField(cashMemo, { cashMemo = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("备注（选填）") }, singleLine = true, shape = RoundedCornerShape(13.dp), colors = fieldColors())
                    recentCashEvents.forEach { event ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val label = when (event.type) {
                                AccountCashEventType.DEPOSIT -> "入金"
                                AccountCashEventType.WITHDRAWAL -> "出金"
                                AccountCashEventType.DIVIDEND -> "分红"
                                else -> "现金事件"
                            }
                            Text("${event.day} · $label${event.memo.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}", color = Muted, fontSize = 10.sp, maxLines = 1)
                            Text("${if (event.type == AccountCashEventType.WITHDRAWAL) "−" else "+"}¥${event.amount.portfolioMoney()}", color = Ink, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        MarketSegmentedControl(
            options = listOf("基金", "黄金"),
            selectedIndex = if (filter == AssetKind.FUND) 0 else 1,
            modifier = Modifier.fillMaxWidth()
        ) { index ->
            filter = if (index == 0) AssetKind.FUND else AssetKind.GOLD
            selected = null; search = ""; fundFeatureState.clearSearch()
        }
        if (selected == null) {
            MarketSearchField(
                value = search,
                onValueChange = { search = it; fundFeatureState.search(it) },
                placeholder = if (filter == AssetKind.FUND) "搜索基金名称或代码，例如 110011" else "搜索黄金品种，例如 Au99.99",
                modifier = Modifier.fillMaxWidth(),
                onClear = { search = ""; fundFeatureState.clearSearch() }
            )
            // The market search returns the same code the local catalogue already knows about; keep the
            // richer market row and only surface local-only assets here.
            val remoteCodes = fundFeatureState.results.map { it.code }.toSet()
            val localCandidates = if (filter == AssetKind.FUND && search.isNotBlank()) {
                candidates.filterNot { it.symbol in remoteCodes }
            } else {
                candidates
            }
            if (localCandidates.isNotEmpty()) {
                Text(
                    if (filter == AssetKind.FUND && search.isNotBlank()) "本机标的 · ${localCandidates.size}"
                    else "搜索结果 · ${localCandidates.size}",
                    color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium
                )
                Card(Modifier.fillMaxWidth().heightIn(max = 370.dp).verticalScroll(rememberScrollState()), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = White)) {
                    Column {
                        localCandidates.forEach { asset ->
                            TextButton(onClick = {
                                selected = asset; search = ""; transferTarget = null; transferSearch = ""
                                asset.toFundSearchResult()?.let(fundFeatureState::openProfile) ?: fundFeatureState.clearProfile()
                            }, modifier = Modifier.fillMaxWidth().height(66.dp)) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(asset.name, color = Ink, fontWeight = FontWeight.Medium)
                                    Text("${asset.category}  ·  ${asset.symbol}", color = Muted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            } else if (search.isNotBlank() && (filter != AssetKind.FUND ||
                    fundFeatureState.completedQuery == search.trim() && fundFeatureState.results.isEmpty())) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = White)) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(if (fundFeatureState.searchError == null) "没有找到“$search”" else "搜索暂不可用", color = Ink, fontWeight = FontWeight.SemiBold)
                        Text("你可以手动补充名称和代码。", color = Muted, fontSize = 12.sp)
                        OutlinedTextField(customName, { customName = it }, label = { Text("基金 / 黄金名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = fieldColors())
                        OutlinedTextField(customCode, { customCode = it }, label = { Text("代码（选填）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = fieldColors())
                        Button(onClick = {
                            val displayName = customName.ifBlank { search.trim() }
                            val identifier = customCode.ifBlank { "LOCAL_${displayName.hashCode().toUInt().toString(16)}" }
                            runCatching { ledger.addCustomAsset(identifier, displayName, filter) }
                                .onSuccess {
                                    selected = it; search = ""; customName = ""; customCode = ""
                                    transferTarget = null; transferSearch = ""
                                    it.toFundSearchResult()?.let(fundFeatureState::openProfile) ?: fundFeatureState.clearProfile()
                                }
                                .onFailure { message = it.message ?: "添加失败"; messageIsError = true }
                        }) { Text("使用这个标的") }
                    }
                }
            } else if (search.isBlank()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (filter == AssetKind.FUND) "常用基金" else "常用黄金", Modifier.weight(1f), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(if (filter == AssetKind.GOLD) "Au99.99 可联动上金所" else "名称可搜简称", color = Muted, fontSize = 10.sp)
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    quickPicks.forEach { asset ->
                        MarketChoiceChip(
                            label = asset.name.take(9),
                            selected = false,
                            onClick = {
                                val chosen = if (asset.symbol == "GOLD.SGE.AU9999") {
                                    ledger.findAsset(asset.symbol) ?: ledger.addCustomAsset(asset.symbol, asset.name, asset.kind)
                                } else asset
                                selected = chosen; transferTarget = null; transferSearch = ""
                                chosen.toFundSearchResult()?.let(fundFeatureState::openProfile) ?: fundFeatureState.clearProfile()
                            }
                        )
                    }
                }
            }
            if (selected == null && search.isBlank()) {
                MarketCard {
                    MarketSectionHeader("记一笔的三步", caption = "份额与成本按确认流水重算")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LedgerStep("1", "选标的", "搜索或常用", Modifier.weight(1f))
                        LedgerStep("2", "填金额", "净值可后补", Modifier.weight(1f))
                        LedgerStep("3", "保存", "5 秒内撤销", Modifier.weight(1f))
                    }
                    Text(
                        "已有持仓可切到「期初持仓」，只登记份额与历史成本，不补造过去的买入流水。",
                        color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                    )
                }
            }
            if (filter == AssetKind.FUND && search.isNotBlank()) {
                FundSearchResultsPanel(
                    query = search,
                    results = fundFeatureState.results,
                    isLoading = fundFeatureState.isSearching,
                    completedQuery = fundFeatureState.completedQuery,
                    error = fundFeatureState.searchError,
                    onView = { result ->
                        fundFeatureState.openProfile(result)
                        fundDetailOpen = true
                    },
                    onRetry = fundFeatureState::retrySearch,
                    onUse = { result ->
                        runCatching { ledger.addFundAsset(result.code, result.name, result.category) }
                            .onSuccess { asset ->
                                selected = asset
                                if (fundFeatureState.profileTarget?.code != result.code ||
                                    fundFeatureState.profile == null && !fundFeatureState.isProfileLoading) {
                                    fundFeatureState.openProfile(result)
                                }
                                search = ""
                                transferTarget = null
                                transferSearch = ""
                                onChanged()
                            }
                            .onFailure { error -> message = error.message ?: "无法添加该基金"; messageIsError = true }
                    }
                )
            }
            fundFeatureState.profileTarget?.let { target ->
                if (filter == AssetKind.FUND && (selected == null || selected?.symbol == target.code)) {
                    FundProfileSummary(
                        profile = fundFeatureState.profile,
                        isLoading = fundFeatureState.isProfileLoading,
                        error = fundFeatureState.profileError,
                        navEstimate = fundFeatureState.navEstimate,
                        onOpenDetails = { fundDetailOpen = true },
                        onClose = {
                            fundFeatureState.clearProfile()
                            fundDetailOpen = false
                        },
                        onRetry = fundFeatureState::retryProfile,
                        fallbackName = target.name,
                        fallbackCode = target.code,
                        onUse = {
                            if (selected == null) {
                                runCatching {
                                    ledger.addFundAsset(target.code, target.name, fundFeatureState.profile?.fundType ?: target.category)
                                }
                                    .onSuccess { asset -> selected = asset; search = ""; onChanged() }
                                    .onFailure { error -> message = error.message ?: "无法添加该基金"; messageIsError = true }
                            }
                        }
                    )
                }
            }
        } else {
            val selectedIsFundProfile = selected?.kind == AssetKind.FUND &&
                selected?.symbol == fundFeatureState.profileTarget?.code
            if (!selectedIsFundProfile) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = SoftBlue)) {
                    Row(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(selected!!.name, color = Ink, fontWeight = FontWeight.SemiBold)
                            Text("${selected!!.category}  ·  ${selected!!.symbol}", color = Muted, fontSize = 11.sp)
                        }
                        TextButton(onClick = { selected = null; transferTarget = null; transferSearch = "" }) { Text("更换") }
                    }
                }
            }
            if (selectedIsFundProfile) {
                val target = fundFeatureState.profileTarget
                FundProfileSummary(
                    profile = fundFeatureState.profile,
                    isLoading = fundFeatureState.isProfileLoading,
                    error = fundFeatureState.profileError,
                    navEstimate = fundFeatureState.navEstimate,
                    onOpenDetails = { fundDetailOpen = true },
                    onClose = {
                        fundFeatureState.clearProfile()
                        fundDetailOpen = false
                    },
                    onRetry = fundFeatureState::retryProfile,
                    fallbackName = target?.name ?: selected?.name,
                    fallbackCode = target?.code ?: selected?.symbol,
                    onChange = {
                        selected = null
                        transferTarget = null
                        transferSearch = ""
                    }
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                fun chooseTrade(next: TradeSide) {
                    side = next; amount = ""; units = ""; price = ""; fee = "0"; note = ""; showFee = false
                    if (next == TradeSide.SHARE_SPLIT) shareFactor = ""
                    if (next == TradeSide.ASSET_TRANSFER) {
                        transferSearch = ""; transferTarget = null; transferUnits = ""; transferCost = ""
                    }
                }
                listOf(
                    TradeSide.BUY to "买入",
                    TradeSide.SELL to "卖出 / 赎回",
                    TradeSide.OPENING_POSITION to "期初持仓",
                    TradeSide.CASH_DIVIDEND to "现金分红",
                    TradeSide.DIVIDEND_REINVESTMENT to "红利再投",
                    TradeSide.SHARE_SPLIT to "份额拆分",
                    TradeSide.ASSET_TRANSFER to "资产转移"
                ).forEach { (kind, label) ->
                    MarketChoiceChip(label = label, selected = side == kind) { chooseTrade(kind) }
                }
            }
            if (side == TradeSide.OPENING_POSITION) {
                NumericField(units, { units = it }, if (selected!!.kind == AssetKind.GOLD) "当前持有克数 · 必填" else "当前持有份额 · 必填", Modifier.fillMaxWidth())
                NumericField(amount, { amount = it }, "历史持仓成本（元）· 必填", Modifier.fillMaxWidth())
                InfoCard("从今天建立记录", "不补造过去的买入流水或现金变化；较早的收益不会计入今天的周期盈亏。", Ink)
            } else if (side == TradeSide.BUY) {
                NumericField(amount, { amount = it }, "买入金额（元）· 必填", Modifier.fillMaxWidth())
            } else if (side == TradeSide.SELL) {
                NumericField(units, { units = it }, if (selected!!.kind == AssetKind.GOLD) "卖出克数" else "赎回份额", Modifier.fillMaxWidth())
            } else if (side == TradeSide.CASH_DIVIDEND || side == TradeSide.DIVIDEND_REINVESTMENT) {
                NumericField(amount, { amount = it }, "税前分红金额（元）· 必填", Modifier.fillMaxWidth())
                NumericField(fee, { fee = it }, "代扣税费（元；无税填 0）", Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("分红备注（选填）") }, singleLine = true, shape = RoundedCornerShape(13.dp), colors = fieldColors())
                if (side == TradeSide.CASH_DIVIDEND) {
                    InfoCard(
                        "按到账净额入账",
                        if (cashBalance == null) "税后分红计入该标的已实现收益。启用账户现金后，新分红也会记入现金余额。"
                        else "分红金额扣除代扣税费后进入账户现金；税后分红同时计入该标的已实现收益。",
                        Ink
                    )
                } else {
                    NumericField(price, { price = it }, if (selected!!.kind == AssetKind.GOLD) "再投资克价 · 必填" else "再投资净值 · 必填", Modifier.fillMaxWidth())
                    InfoCard("税后分红再投资", "税后金额按净值换算份额；本事件不写入现金流水，新增份额按再投资净值计入成本。", Ink)
                }
            } else if (side == TradeSide.SHARE_SPLIT) {
                NumericField(shareFactor, { shareFactor = it }, "每 1 份换算为多少份 · 必填（如 2 或 0.5）", Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("拆分说明（选填）") }, singleLine = true, shape = RoundedCornerShape(13.dp), colors = fieldColors())
                InfoCard("只调整份额", "总成本保持不变；单位成本按比例变化。旧报价会失效，请录入拆分后的新价格。", Ink)
            } else if (side == TradeSide.ASSET_TRANSFER) {
                OutlinedTextField(
                    transferSearch,
                    { transferSearch = it; transferTarget = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("转入标的名称或代码") },
                    singleLine = true,
                    shape = RoundedCornerShape(13.dp),
                    colors = fieldColors()
                )
                val transferMatches = remember(transferSearch, selected?.symbol) {
                    if (transferSearch.isBlank()) emptyList() else ledger.assets().filter {
                        it.symbol != selected?.symbol &&
                            (it.name.contains(transferSearch.trim(), true) || it.symbol.contains(transferSearch.trim(), true))
                    }.take(8)
                }
                transferTarget?.let { target ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("转入：${target.name} · ${target.symbol}", Modifier.weight(1f), color = Ink, fontSize = 12.sp)
                        TextButton(onClick = { transferTarget = null; transferSearch = "" }) { Text("更换") }
                    }
                } ?: transferMatches.forEach { target ->
                    TextButton(onClick = { transferTarget = target; transferSearch = target.name }) {
                        Text("${target.name} · ${target.symbol}", color = Ink)
                    }
                }
                NumericField(units, { units = it }, if (selected!!.kind == AssetKind.GOLD) "转出克数 · 必填" else "转出份额 · 必填", Modifier.fillMaxWidth())
                NumericField(transferUnits, { transferUnits = it }, if (transferTarget?.kind == AssetKind.GOLD) "转入克数 · 必填" else "转入份额 · 必填", Modifier.fillMaxWidth())
                NumericField(transferCost, { transferCost = it }, "转移成本（元）· 必填", Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("转移备注（选填）") }, singleLine = true, shape = RoundedCornerShape(13.dp), colors = fieldColors())
                InfoCard("成对转移", "成本按转出标的的加权平均成本校验，并等额计入转入标的；现金余额和外部资金流保持不变。", Ink)
            }
            if (side == TradeSide.BUY || side == TradeSide.SELL) {
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
                        } else if (side == TradeSide.CASH_DIVIDEND) {
                            val grossAmount = amount.toBigDecimalOrNull() ?: error("请填写税前分红金额")
                            val withholdingTax = fee.toBigDecimalOrNull() ?: error("请填写代扣税费；无税时填 0")
                            savedTradeId = ledger.addCashDividend(requireNotNull(selected), grossAmount, withholdingTax, note)
                            savedMessage = if (cashBalance == null) {
                                "分红已记入已实现收益；启用现金跟踪后可记录现金余额"
                            } else {
                                "现金分红已记入，税后金额进入现金与收益"
                            }
                        } else if (side == TradeSide.DIVIDEND_REINVESTMENT) {
                            val grossAmount = amount.toBigDecimalOrNull() ?: error("请填写税前分红金额")
                            val withholdingTax = fee.toBigDecimalOrNull() ?: error("请填写代扣税费；无税时填 0")
                            val reinvestmentPrice = price.toBigDecimalOrNull() ?: error("请填写再投资净值 / 克价")
                            savedTradeId = ledger.addDividendReinvestment(
                                requireNotNull(selected), grossAmount, withholdingTax, reinvestmentPrice, note
                            )
                            savedMessage = "红利再投已记录；税后分红换算为新增份额，现金余额不变"
                        } else if (side == TradeSide.SHARE_SPLIT) {
                            val factor = shareFactor.toBigDecimalOrNull() ?: error("请填写每份换算比例")
                            savedTradeId = ledger.addShareSplit(requireNotNull(selected), factor, note)
                            savedMessage = "份额拆分已记录；总成本不变，请更新拆分后的报价"
                        } else if (side == TradeSide.ASSET_TRANSFER) {
                            val sourceUnits = units.toBigDecimalOrNull() ?: error("请填写转出份额")
                            val destinationUnits = transferUnits.toBigDecimalOrNull() ?: error("请填写转入份额")
                            val declaredCost = transferCost.toBigDecimalOrNull() ?: error("请填写转移成本")
                            savedTradeId = ledger.addAssetTransfer(
                                requireNotNull(selected), requireNotNull(transferTarget) { "请先选择转入标的" },
                                sourceUnits, destinationUnits, declaredCost, note
                            )
                            savedMessage = "资产转移已记录；成本已在两个标的间转移，现金余额不变"
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
                        amount = ""; units = ""; price = ""; fee = "0"; showFee = false; note = ""; shareFactor = ""
                        transferSearch = ""; transferTarget = null; transferUnits = ""; transferCost = ""
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
                colors = ButtonDefaults.buttonColors(containerColor = Blue)
            ) {
                Text(when (side) {
                    TradeSide.BUY -> "记下买入${amount.takeIf { it.isNotBlank() }?.let { " ¥$it" } ?: ""}"
                    TradeSide.SELL -> "记下赎回"
                    TradeSide.OPENING_POSITION -> "录入期初持仓"
                    TradeSide.CASH_DIVIDEND -> "记下现金分红"
                    TradeSide.DIVIDEND_REINVESTMENT -> "记下红利再投"
                    TradeSide.SHARE_SPLIT -> "记录份额拆分"
                    TradeSide.ASSET_TRANSFER -> "记录资产转移"
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
                            Text(if (trade.side == TradeSide.BUY) "申购 ¥${trade.amount.portfolioMoney()} · ${trade.day}" else "赎回 ${trade.units?.portfolioQty()} 份 · ${trade.day}", color = Muted, fontSize = 11.sp)
                        }
                        TextButton(onClick = { confirmTarget = trade; confirmPrice = "" }) { Text("补录净值") }
                    }
                }
            }
        }
        if (selected != null) Text(
            when (side) {
                TradeSide.OPENING_POSITION -> "录入日期：今天 · 历史收益不追溯"
                TradeSide.CASH_DIVIDEND -> "到账日期：今天 · 税后金额按分红到账记录"
                TradeSide.DIVIDEND_REINVESTMENT -> "再投资日期：今天 · 按录入净值换算新增份额"
                TradeSide.SHARE_SPLIT -> "生效日期：今天 · 记录按 1 份换算的份额倍数"
                TradeSide.ASSET_TRANSFER -> "转移日期：今天 · 份额和成本作为同一事件提交"
                else -> "交易时间：今天 · 可稍后补充确认净值"
            },
            color = Muted, fontSize = 11.sp
        )
    }

    val detailTarget = fundFeatureState.profileTarget
    if (fundDetailOpen && detailTarget != null) {
        FundDetailSheet(onDismiss = { fundDetailOpen = false }) {
            FundProfileCard(
                profile = fundFeatureState.profile,
                isLoading = fundFeatureState.isProfileLoading,
                error = fundFeatureState.profileError,
                onUse = if (selected == null) ({
                    runCatching {
                        ledger.addFundAsset(
                            detailTarget.code,
                            detailTarget.name,
                            fundFeatureState.profile?.fundType ?: detailTarget.category
                        )
                    }
                        .onSuccess { asset -> selected = asset; search = ""; onChanged() }
                        .onFailure { error -> message = error.message ?: "无法添加该基金"; messageIsError = true }
                }) else null,
                onRetry = fundFeatureState::retryProfile,
                historyCache = fundFeatureState.navHistoryCache,
                isBackfillingHistory = fundFeatureState.isBackfillingHistory,
                historySyncMessage = fundFeatureState.historySyncMessage,
                onBackfillFullHistory = fundFeatureState::backfillFullHistory,
                isWatchlisted = fundFeatureState.isWatchlisted,
                watchlistMessage = fundFeatureState.watchlistMessage,
                onToggleWatchlist = { fundFeatureState.toggleWatchlist(onChanged) },
                navEstimate = fundFeatureState.navEstimate,
                isEstimatingNav = fundFeatureState.isEstimatingNav,
                onRefreshEstimate = if (canRefreshFundEstimate) ({
                    viewedFundProfile?.let { fundFeatureState.estimateNav(it, trackingIndexQuote, forceRefresh = true) }
                }) else null,
                moneyYieldEstimate = fundFeatureState.moneyYieldEstimate,
                listedPriceComparison = fundFeatureState.listedPriceComparison,
                isRefreshingListedPrice = fundFeatureState.isRefreshingListedPrice,
                onRefreshListedPrice = {
                    viewedFundProfile?.let { fundFeatureState.loadListedMarketPrice(it, forceRefresh = true) }
                }
            )
        }
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

/** One numbered step in the "how to record" card; keeps the empty form from being a dead page. */
@Composable
private fun LedgerStep(number: String, title: String, hint: String, modifier: Modifier = Modifier) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    Column(
        modifier
            .clip(RoundedCornerShape(geometry.chip))
            .background(palette.surfaceAlt)
            .padding(horizontal = geometry.md, vertical = geometry.sm),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(number, color = palette.accent, style = MarketType.numberSmall, fontWeight = FontWeight.Bold)
        Text(title, color = palette.ink, style = MarketType.label, fontWeight = FontWeight.Medium)
        Text(hint, color = palette.muted, style = MarketType.caption, maxLines = 1)
    }
}

@Composable
internal fun NumericField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val geometry = MarketTheme.geometry
    OutlinedTextField(
        value, onValueChange, modifier = modifier, singleLine = true,
        label = { Text(label, style = MarketType.caption) },
        textStyle = MarketType.numberLarge.copy(fontWeight = FontWeight.Medium),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = RoundedCornerShape(geometry.card), colors = fieldColors()
    )
}
