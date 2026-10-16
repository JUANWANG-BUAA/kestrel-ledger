package cn.market.assistant.domain.portfolio

import java.math.BigDecimal

enum class AssetKind { FUND, GOLD }
enum class TradeSide { BUY, SELL, OPENING_POSITION, CASH_DIVIDEND, DIVIDEND_REINVESTMENT, SHARE_SPLIT, ASSET_TRANSFER }

data class AssetInfo(
    val symbol: String,
    val name: String,
    val kind: AssetKind,
    val category: String,
    val marketQuoteSecid: String? = null
)

data class Trade(
    val id: Long,
    val asset: AssetInfo,
    val side: TradeSide,
    val day: String,
    val amount: BigDecimal,
    val units: BigDecimal?,
    val price: BigDecimal?,
    val fee: BigDecimal,
    val confirmed: Boolean,
    val reversedAt: String? = null,
    val reversalReason: String? = null,
    val memo: String = "",
    val shareFactor: BigDecimal? = null,
    val relatedAsset: AssetInfo? = null,
    val relatedUnits: BigDecimal? = null
) {
    val isReversed: Boolean get() = reversedAt != null
}

/** Manually observed bank bid/ask; the bid is the account owner's realizable mark. */
data class ManualGoldQuote(
    val assetSymbol: String,
    val bankBuybackPrice: BigDecimal,
    val bankSellPrice: BigDecimal,
    val observedAt: String,
    val validUntil: String
)

data class Position(
    val asset: AssetInfo,
    val units: BigDecimal,
    val cost: BigDecimal,
    val realized: BigDecimal,
    val quote: BigDecimal?,
    val quoteTime: String?,
    val quoteSource: String? = null,
    val manualGoldQuote: ManualGoldQuote? = null
) {
    val quoteExpired: Boolean get() = quoteSource == "MANUAL_BANK_BUYBACK" &&
        (manualGoldQuote?.validUntil?.let { validUntil ->
            runCatching { !java.time.Instant.now().isBefore(java.time.Instant.parse(validUntil)) }.getOrDefault(true)
        } ?: true)

    val value: BigDecimal? get() = when {
        units.signum() == 0 -> BigDecimal.ZERO
        quoteExpired -> null
        else -> quote?.multiply(units)
    }
    val holdingProfit: BigDecimal? get() = value?.subtract(cost)
}
