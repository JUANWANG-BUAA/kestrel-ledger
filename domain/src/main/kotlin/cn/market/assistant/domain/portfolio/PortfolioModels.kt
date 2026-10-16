package cn.market.assistant.domain.portfolio

import java.math.BigDecimal

enum class AssetKind { FUND, GOLD }
enum class TradeSide { BUY, SELL, OPENING_POSITION }

data class AssetInfo(val symbol: String, val name: String, val kind: AssetKind, val category: String)

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
    val reversalReason: String? = null
) {
    val isReversed: Boolean get() = reversedAt != null
}

data class Position(
    val asset: AssetInfo,
    val units: BigDecimal,
    val cost: BigDecimal,
    val realized: BigDecimal,
    val quote: BigDecimal?,
    val quoteTime: String?,
    val quoteSource: String? = null
) {
    val value: BigDecimal? get() = quote?.multiply(units)
    val holdingProfit: BigDecimal? get() = value?.subtract(cost)
}
