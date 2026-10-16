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

/** One publicly quoted constituent of an industry/concept board. */
data class MarketBoardMember(
    val secid: String,
    val code: String,
    val name: String,
    val price: Double,
    val changePct: Double,
    val turnoverRatePct: Double?,
    val marketCap: Double?,
    val quoteTime: Long,
    val fetchedAt: Long,
    val source: String
)

/**
 * Cached constituent snapshot for one board. The public feed pages by market cap, so `totalCount`
 * records how many constituents the source reported and `isTruncated` tells the UI the list is not
 * the full membership.
 */
data class MarketBoardMemberSnapshot(
    val boardId: String,
    val boardName: String,
    val members: List<MarketBoardMember>,
    val totalCount: Int,
    val fetchedAt: Long,
    val source: String
) {
    val isTruncated: Boolean get() = totalCount > members.size
}

/** Deterministic sorting and concentration helpers for a constituent list. */
object BoardMemberAnalytics {
    enum class MemberSort(val label: String) {
        CHANGE_DESC("涨幅优先"),
        CHANGE_ASC("跌幅优先"),
        MARKET_CAP_DESC("市值优先")
    }

    fun sortMembers(members: List<MarketBoardMember>, sort: MemberSort): List<MarketBoardMember> = when (sort) {
        MemberSort.CHANGE_DESC -> members.sortedWith(
            compareByDescending<MarketBoardMember> { it.changePct }.thenByDescending { it.marketCap ?: 0.0 }
        )
        MemberSort.CHANGE_ASC -> members.sortedWith(
            compareBy<MarketBoardMember> { it.changePct }.thenByDescending { it.marketCap ?: 0.0 }
        )
        MemberSort.MARKET_CAP_DESC -> members.sortedByDescending { it.marketCap ?: 0.0 }
    }

    /** Share of the known market cap held by the largest [topN] members, in percent. */
    fun concentrationTopN(members: List<MarketBoardMember>, topN: Int = 5): Double? {
        if (topN <= 0) return null
        val caps = members.mapNotNull { it.marketCap?.takeIf { cap -> cap.isFinite() && cap > 0.0 } }
        if (caps.isEmpty()) return null
        val total = caps.sum()
        if (total <= 0.0) return null
        return caps.sortedDescending().take(topN).sum() / total * 100.0
    }

    /** Advancing and declining member counts for the fetched list. */
    fun advanceDecline(members: List<MarketBoardMember>): Pair<Int, Int> =
        members.count { it.changePct > 0.0 } to members.count { it.changePct < 0.0 }
}
