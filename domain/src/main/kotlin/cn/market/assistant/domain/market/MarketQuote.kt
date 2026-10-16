package cn.market.assistant.domain.market

/** Provider-neutral market quote used by data adapters and app features. */
data class MarketQuote(
    val secid: String,
    val name: String,
    val price: Double,
    val change: Double,
    val changePct: Double,
    val quoteTime: Long,
    val fetchedAt: Long,
    val source: String = "eastmoney"
)

data class QuoteRefreshResult(
    val quotes: Map<String, MarketQuote>,
    val refreshedCount: Int,
    val sourceFailureCount: Int = 0,
    val coverageIncomplete: Boolean = false
)
