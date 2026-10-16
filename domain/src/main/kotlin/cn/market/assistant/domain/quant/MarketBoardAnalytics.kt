package cn.market.assistant.domain.quant

import cn.market.assistant.domain.market.MarketBoard

data class MarketBoardBreadth(
    val total: Int,
    val advancing: Int,
    val declining: Int,
    val unchanged: Int,
    val advanceRatioPct: Double,
    val averageChangePct: Double,
    val medianChangePct: Double,
    val breadthIndex: Double
)

/** Descriptive cross-section statistics for the selected industry or concept board set. */
object MarketBoardAnalytics {
    fun breadth(boards: List<MarketBoard>): MarketBoardBreadth {
        if (boards.isEmpty()) return MarketBoardBreadth(0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0)

        val advancing = boards.count { it.changePct > 0.0 }
        val declining = boards.count { it.changePct < 0.0 }
        val unchanged = boards.size - advancing - declining
        val sortedChanges = boards.map { it.changePct }.sorted()
        val middle = sortedChanges.size / 2
        val median = if (sortedChanges.size % 2 == 0) {
            (sortedChanges[middle - 1] + sortedChanges[middle]) / 2.0
        } else {
            sortedChanges[middle]
        }
        return MarketBoardBreadth(
            total = boards.size,
            advancing = advancing,
            declining = declining,
            unchanged = unchanged,
            advanceRatioPct = advancing * 100.0 / boards.size,
            averageChangePct = boards.sumOf { it.changePct } / boards.size,
            medianChangePct = median,
            breadthIndex = (advancing - declining) * 100.0 / boards.size
        )
    }
}
