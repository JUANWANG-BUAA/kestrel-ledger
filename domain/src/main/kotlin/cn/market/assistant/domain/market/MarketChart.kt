package cn.market.assistant.domain.market

/** Provider-neutral OHLC bar used by chart features and local cache adapters. */
data class MarketOhlcBar(
    val day: String,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Double = 0.0
)

enum class ChartPeriod(val label: String, val cacheKey: String) {
    INTRADAY("实时", "m30"),
    FIVE_DAY("5日", "m30"),
    DAY("日K", "day"),
    WEEK("周K", "week"),
    MONTH("月K", "month")
}

data class CachedMarketChart(
    val bars: List<MarketOhlcBar>,
    val cachedAt: Long,
    val refreshed: Boolean = false,
    val source: String? = null
)
