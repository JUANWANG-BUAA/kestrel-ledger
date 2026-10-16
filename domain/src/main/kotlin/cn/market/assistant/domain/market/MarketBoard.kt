package cn.market.assistant.domain.market

/** A market-wide industry or concept index row with source metadata. */
data class MarketBoard(
    val id: String,
    val type: MarketBoardType,
    val name: String,
    val price: Double,
    val change: Double,
    val changePct: Double,
    val turnoverAmount: Double?,
    val turnoverRatePct: Double?,
    val marketCap: Double?,
    val advancingCount: Int,
    val decliningCount: Int,
    val quoteTime: Long,
    val fetchedAt: Long,
    val source: String
)

enum class MarketBoardType(val label: String) {
    INDUSTRY("行业"),
    CONCEPT("概念")
}

/** Last complete board-list snapshot. Partial network responses never replace this cache. */
data class MarketBoardSnapshot(
    val type: MarketBoardType,
    val boards: List<MarketBoard>,
    val expectedCount: Int,
    val fetchedAt: Long,
    val source: String = "eastmoney"
) {
    val isComplete: Boolean get() = expectedCount > 0 && boards.size >= expectedCount
}
