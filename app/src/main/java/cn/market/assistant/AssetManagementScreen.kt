package cn.market.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.market.assistant.domain.portfolio.AssetKind
import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.domain.market.MarketRepository
import cn.market.assistant.domain.portfolio.PortfolioQuoteRouter
import cn.market.assistant.domain.portfolio.Position
import cn.market.assistant.domain.portfolio.ManualGoldQuote
import cn.market.assistant.domain.portfolio.Trade
import cn.market.assistant.domain.portfolio.TradeSide
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.launch

@Composable
internal fun AssetsScreen(
    positions: List<Position>, trades: List<Trade>, ledger: Ledger, marketRepository: MarketRepository,
    marketQuotes: Map<String, MarketQuote>, onMarketQuoteFetched: (MarketQuote) -> Unit,
    onChanged: () -> Unit,
    onExport: () -> Unit, onImport: () -> Unit, onRecord: () -> Unit,
    dataMessage: String, dataMessageIsError: Boolean,
    modifier: Modifier = Modifier
) {
    var showAllTrades by remember { mutableStateOf(false) }
    var reversalTarget by remember { mutableStateOf<Trade?>(null) }
    var reversalReason by remember { mutableStateOf("") }
    var reversalError by remember { mutableStateOf<String?>(null) }
    var reversalMessage by remember { mutableStateOf("") }
    ScreenColumn(modifier) {
        BrandHeader("持仓")
        Text("持仓按确认流水计算。补充当前净值或克价后，账面市值与盈亏会同步更新。", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
        if (positions.isEmpty()) {
            // Right after the first purchase the list is still empty, so point at confirmation
            // instead of telling the user to do what they just did.
            val pendingTrades = trades.count { !it.confirmed && !it.isReversed }
            MarketEmptyState(
                icon = "assets",
                title = if (pendingTrades > 0) "有 $pendingTrades 笔交易待确认" else "还没有持仓",
                body = if (pendingTrades > 0) "补录确认净值后就会生成持仓；确认前不计入份额和盈亏。"
                else "记下第一笔买入或期初持仓，份额、成本与盈亏会在这里汇总。",
                actionLabel = if (pendingTrades > 0) null else "去记一笔",
                onAction = onRecord
            )
        }
        positions.forEach { position ->
            PositionCard(position)
            QuoteEditor(position, marketQuotes, ledger, marketRepository, onMarketQuoteFetched) { onChanged() }
        }
        MarketCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MarketSectionHeader("交易流水", caption = if (trades.isEmpty()) "暂无记录" else "共 ${trades.size} 笔") {
                    Text("${trades.size} 笔", color = Muted, style = MarketType.label)
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
        MarketCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MarketSectionHeader("账本数据与备份", caption = "${trades.size} 笔交易 · ${ledger.assets().size} 个可选标的")
                Text(
                    "数据保存在本机。新备份使用密码加密，可用于换机或恢复；请自行保存密码。旧版未加密备份仍可导入。",
                    color = Muted, fontSize = 11.sp, lineHeight = 16.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onExport, shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
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
        TradeSide.CASH_DIVIDEND -> "现金分红"
        TradeSide.DIVIDEND_REINVESTMENT -> "红利再投"
        TradeSide.SHARE_SPLIT -> "份额拆分"
        TradeSide.ASSET_TRANSFER -> "资产转移"
    }
    val unit = if (trade.asset.kind == AssetKind.GOLD) "克" else "份"
    val details = when {
        !trade.confirmed -> "待确认 · 买入金额 ¥${trade.amount.portfolioMoney()}"
        trade.side == TradeSide.OPENING_POSITION -> "持有 ${trade.units?.portfolioQty() ?: "—"} $unit · 历史成本 ¥${trade.amount.portfolioMoney()}"
        trade.side == TradeSide.CASH_DIVIDEND -> "税前 ¥${trade.amount.portfolioMoney()} · 税费 ¥${trade.fee.portfolioMoney()} · 到账 ¥${(trade.amount - trade.fee).portfolioMoney()}"
        trade.side == TradeSide.DIVIDEND_REINVESTMENT -> buildString {
            append("税前 ¥${trade.amount.portfolioMoney()} · 税费 ¥${trade.fee.portfolioMoney()}")
            append(" · 再投 ¥${(trade.amount - trade.fee).portfolioMoney()}")
            append(" · ${trade.units?.portfolioQty() ?: "—"} $unit")
            trade.price?.let { append(" × ${it.setScale(4, RoundingMode.HALF_UP).toPlainString()}") }
        }
        trade.side == TradeSide.SHARE_SPLIT -> "每 1 份换算为 ${trade.shareFactor?.stripTrailingZeros()?.toPlainString() ?: "—"} 份 · 总成本不变"
        trade.side == TradeSide.ASSET_TRANSFER -> buildString {
            append("转出 ${trade.units?.portfolioQty() ?: "—"} ${if (trade.asset.kind == AssetKind.GOLD) "克" else "份"}")
            append(" → ${trade.relatedAsset?.name ?: "未知标的"} ${trade.relatedUnits?.portfolioQty() ?: "—"}")
            append(" ${if (trade.relatedAsset?.kind == AssetKind.GOLD) "克" else "份"}")
            append(" · 成本 ¥${trade.amount.portfolioMoney()}")
        }
        else -> buildString {
            append("${trade.units?.portfolioQtyDisplay() ?: "—"} $unit")
            trade.price?.let { append(" × ${it.setScale(4, RoundingMode.HALF_UP).toPlainString()}") }
            if (trade.fee.signum() > 0) append(" · 手续费 ¥${trade.fee.portfolioMoney()}")
        }
    }.let { detail ->
        val withMemo = trade.memo.takeIf { it.isNotBlank() }?.let { "$detail · 备注：$it" } ?: detail
        if (trade.isReversed) "$withMemo · 原因：${trade.reversalReason}" else withMemo
    }
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
            if (trade.side == TradeSide.SHARE_SPLIT) {
                Text("×${trade.shareFactor?.stripTrailingZeros()?.toPlainString() ?: "—"}", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            } else if (trade.side == TradeSide.ASSET_TRANSFER) {
                Text("转移", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            } else {
                val displayedAmount = if (
                    trade.side == TradeSide.CASH_DIVIDEND || trade.side == TradeSide.DIVIDEND_REINVESTMENT
                ) trade.amount - trade.fee else trade.amount
                Text("¥${displayedAmount.portfolioMoneyGrouped()}", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            if (!trade.isReversed) TextButton(
                onClick = onReverse,
                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)
            ) { Text(if (trade.confirmed) "冲正" else "作废", color = ErrorColor, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun QuoteEditor(
    position: Position,
    marketQuotes: Map<String, MarketQuote>,
    ledger: Ledger,
    marketRepository: MarketRepository,
    onMarketQuoteFetched: (MarketQuote) -> Unit,
    onSaved: () -> Unit
) {
    val asset = position.asset
    val scope = rememberCoroutineScope()
    var value by remember(asset.symbol, position.quote) { mutableStateOf(position.quote?.toPlainString() ?: "") }
    var bankBuybackValue by remember(asset.symbol, position.manualGoldQuote, position.quote) {
        mutableStateOf((position.manualGoldQuote?.bankBuybackPrice ?: position.quote)?.toPlainString() ?: "")
    }
    var bankSellValue by remember(asset.symbol, position.manualGoldQuote) {
        mutableStateOf(position.manualGoldQuote?.bankSellPrice?.toPlainString() ?: "")
    }
    var bankMode by remember(asset.symbol, position.quoteSource) {
        mutableStateOf(position.quoteSource == "MANUAL_BANK_BUYBACK")
    }
    var validityHours by remember(asset.symbol, position.manualGoldQuote) {
        mutableStateOf(position.manualGoldQuote?.let(::manualGoldValidityHours) ?: 24)
    }
    var routeError by remember(asset.symbol) { mutableStateOf<String?>(null) }
    var fetchingListedQuote by remember(asset.symbol) { mutableStateOf(false) }
    val listedFundSecid = remember(asset) { PortfolioQuoteRouter.listedFundQuoteSecid(asset) }
    val routeOptions = remember(asset, marketQuotes) {
        PortfolioQuoteRouter.availableSecids(asset, marketQuotes.values)
    }
    if (routeOptions.isNotEmpty()) {
        Text("报价方式", color = Muted, fontSize = 10.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = asset.marketQuoteSecid == null,
                onClick = {
                    runCatching { ledger.setAssetMarketQuoteSecid(asset, null) }
                        .onSuccess { routeError = null; if (it) onSaved() }
                        .onFailure { routeError = it.message ?: "切换报价方式失败" }
                },
                label = { Text("手动", fontSize = 10.sp) }, colors = assetChipColors()
            )
            routeOptions.forEach { secid ->
                val routeName = marketQuotes[secid]?.name ?: if (secid == "GOLD.SGE.AU9999") "上金所 Au99.99" else secid
                FilterChip(
                    selected = asset.marketQuoteSecid == secid,
                    onClick = {
                        runCatching { ledger.setAssetMarketQuoteSecid(asset, secid) }
                            .onSuccess { routeError = null; if (it) onSaved() }
                            .onFailure { routeError = it.message ?: "切换报价方式失败" }
                    },
                    label = { Text("行情 · ${routeName.take(10)}", fontSize = 10.sp) }, colors = assetChipColors()
                )
            }
        }
    }
    val quoteDetail = when {
        position.quote == null -> "尚无价格；未报价资产不计入总市值"
        position.quoteExpired -> "银行报价已过期；当前持仓估值已暂停"
        else -> "${portfolioQuoteSourceLabel(position.quoteSource)} · ${portfolioQuoteTimeLabel(position.quoteTime)}"
    }
    Text(quoteDetail, color = Muted, fontSize = 10.sp)
    if (listedFundSecid != null) {
        Button(
            enabled = !fetchingListedQuote,
            onClick = {
                scope.launch {
                    fetchingListedQuote = true
                    runCatching { marketRepository.refreshPortfolioEtfQuote(listedFundSecid) }
                        .onSuccess { quote ->
                            onMarketQuoteFetched(quote)
                            routeError = null
                        }
                        .onFailure { routeError = it.message ?: "获取匹配 ETF 行情失败" }
                    fetchingListedQuote = false
                }
            },
            shape = RoundedCornerShape(12.dp)
        ) { Text(if (fetchingListedQuote) "正在查询…" else "按代码获取匹配 ETF 行情") }
    }
    if (asset.marketQuoteSecid == null) {
        if (asset.kind == AssetKind.GOLD) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = !bankMode,
                    onClick = { bankMode = false; routeError = null },
                    label = { Text("单一克价", fontSize = 10.sp) }, colors = assetChipColors()
                )
                FilterChip(
                    selected = bankMode,
                    onClick = { bankMode = true; routeError = null },
                    label = { Text("银行双向报价", fontSize = 10.sp) }, colors = assetChipColors()
                )
            }
        }
        if (asset.kind == AssetKind.GOLD && bankMode) {
            Text("账户持仓按银行买入/回购价估值；卖出价仅用于查看买卖价差。", color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
            NumericField(
                bankBuybackValue,
                { bankBuybackValue = it; routeError = null },
                "银行买入/回购价 · 你卖出可得（元/克）"
            )
            NumericField(
                bankSellValue,
                { bankSellValue = it; routeError = null },
                "银行卖出价 · 你买入需付（元/克）"
            )
            Text("报价有效期", color = Muted, fontSize = 10.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(12, 24, 48, 72).forEach { hours ->
                    FilterChip(
                        selected = validityHours == hours,
                        onClick = { validityHours = hours },
                        label = { Text("${hours}h", fontSize = 10.sp) }, colors = assetChipColors()
                    )
                }
            }
            Button(
                onClick = {
                    runCatching {
                        ledger.setManualBankGoldQuote(
                            asset, BigDecimal(bankBuybackValue), BigDecimal(bankSellValue), validityHours
                        )
                    }.onSuccess { routeError = null; onSaved() }
                        .onFailure { routeError = it.message ?: "保存银行报价失败" }
                },
                shape = RoundedCornerShape(12.dp)
            ) { Text("保存银行双向报价") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                NumericField(value, { value = it; routeError = null }, if (asset.kind == AssetKind.GOLD) "当前克价（元）" else "当前净值（元）", Modifier.weight(1f))
                Button(onClick = {
                    runCatching { ledger.setQuote(asset, BigDecimal(value)) }
                        .onSuccess { routeError = null; onSaved() }
                        .onFailure { routeError = it.message ?: "更新报价失败" }
                }, shape = RoundedCornerShape(12.dp)) { Text("更新") }
            }
        }
    } else if (position.quote == null) {
        Text("已绑定 ${marketQuotes[asset.marketQuoteSecid]?.name ?: asset.marketQuoteSecid}；刷新行情后计入账户。",
            color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
    }
    routeError?.let { Text(it, color = ErrorColor, fontSize = 10.sp, lineHeight = 14.sp) }
    position.manualGoldQuote?.let { quote ->
        val spread = quote.bankSellPrice - quote.bankBuybackPrice
        val spreadPct = spread.multiply(BigDecimal("100")).divide(quote.bankBuybackPrice, 2, RoundingMode.HALF_UP)
        Text(
            "银行回购 ¥${quote.bankBuybackPrice.portfolioMoney()}/克 · 银行卖出 ¥${quote.bankSellPrice.portfolioMoney()}/克 · 价差 ¥${spread.portfolioMoney()}（${spreadPct.toPlainString()}%）",
            color = Ink, fontSize = 10.sp, lineHeight = 15.sp
        )
        Text(
            "${portfolioQuoteTimeLabel(quote.observedAt)} · ${portfolioQuoteValidityLabel(quote.validUntil)}${if (position.quoteExpired) " · 已过期，估值不计入组合" else ""}",
            color = if (position.quoteExpired) ErrorColor else Muted, fontSize = 10.sp, lineHeight = 14.sp
        )
    }
    if (asset.kind == AssetKind.GOLD && asset.marketQuoteSecid == null) {
        val history = remember(asset.symbol, position.manualGoldQuote) { ledger.manualGoldQuoteHistory(asset.symbol, 5) }
        if (history.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("银行报价历史 · 最近 ${history.size} 次", color = Muted, fontSize = 10.sp)
                history.forEach { quote -> ManualGoldQuoteHistoryRow(quote) }
            }
        }
    }
    val routeHint = when {
        asset.kind == AssetKind.GOLD && asset.symbol == "GOLD.SGE.AU9999" && asset.marketQuoteSecid != null ->
            "Au99.99 使用上金所元/克报价；国际金价只作行情对照。"
        asset.kind == AssetKind.FUND && asset.symbol == "518880" && asset.marketQuoteSecid != null ->
            "黄金 ETF 使用对应元/份行情，与克价分开核算。"
        asset.kind == AssetKind.GOLD ->
            "银行积存金可录入回购价与卖出价，持仓按回购价估值；国际现货不用于推算银行报价。"
        else -> null
    }
    routeHint?.let { Text(it, color = Muted, fontSize = 10.sp, lineHeight = 14.sp) }
}

@Composable
private fun ManualGoldQuoteHistoryRow(quote: ManualGoldQuote) {
    val spread = quote.bankSellPrice - quote.bankBuybackPrice
    val expired = runCatching { !Instant.now().isBefore(Instant.parse(quote.validUntil)) }.getOrDefault(true)
    Text(
        "${portfolioQuoteTimeLabel(quote.observedAt)} · 回购 ¥${quote.bankBuybackPrice.portfolioMoney()} · 卖出 ¥${quote.bankSellPrice.portfolioMoney()} · 价差 ¥${spread.portfolioMoney()} · ${if (expired) "已过期" else portfolioQuoteValidityLabel(quote.validUntil)}",
        color = Muted, fontSize = 9.sp, lineHeight = 13.sp
    )
}

private fun manualGoldValidityHours(quote: ManualGoldQuote): Int = runCatching {
    Duration.between(Instant.parse(quote.observedAt), Instant.parse(quote.validUntil)).toHours().toInt()
        .takeIf { it in setOf(12, 24, 48, 72) } ?: 24
}.getOrDefault(24)
