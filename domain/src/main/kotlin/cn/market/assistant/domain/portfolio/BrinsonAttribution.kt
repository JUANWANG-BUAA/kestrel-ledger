package cn.market.assistant.domain.portfolio

import java.math.BigDecimal
import java.math.RoundingMode

/** One allocation bucket for a Brinson-Fachler single-period attribution. */
data class BrinsonSectorInput(
    val key: String,
    val name: String,
    val portfolioWeightPct: BigDecimal?,
    val benchmarkWeightPct: BigDecimal?,
    val portfolioReturnPct: BigDecimal?,
    val benchmarkReturnPct: BigDecimal?,
    /** True for a cash-like bucket whose contribution is exactly zero. */
    val cashLike: Boolean = false
)

data class BrinsonSectorResult(
    val key: String,
    val name: String,
    val portfolioWeightPct: BigDecimal?,
    val benchmarkWeightPct: BigDecimal?,
    val allocationPct: BigDecimal?,
    val selectionPct: BigDecimal?,
    val interactionPct: BigDecimal?,
    val totalPct: BigDecimal?
)

data class BrinsonAttributionResult(
    val sectors: List<BrinsonSectorResult>,
    val benchmarkReturnPct: BigDecimal?,
    val portfolioReturnPct: BigDecimal?,
    val allocationPct: BigDecimal?,
    val selectionPct: BigDecimal?,
    val interactionPct: BigDecimal?,
    val totalPct: BigDecimal?,
    val reconciled: Boolean,
    val missingReason: String?
)

/**
 * Brinson-Fachler single-period attribution: allocation, selection and interaction per bucket.
 *
 * `allocation = (wp - wb) * (rb - rbTotal)`, `selection = wb * (rp - rb)`,
 * `interaction = (wp - wb) * (rp - rb)`. Weights are percentages that must sum to 100; a cash bucket
 * keeps the identity `total = portfolioReturn - benchmarkReturn` intact when the portfolio is not
 * fully invested. Missing inputs never silently become zero: the result carries a reason instead.
 */
object BrinsonAttribution {
    private val WEIGHT_TOLERANCE = BigDecimal("0.05")
    private val HUNDRED = BigDecimal("100")
    private const val SCALE = 4

    fun singlePeriod(sectors: List<BrinsonSectorInput>): BrinsonAttributionResult {
        if (sectors.isEmpty()) {
            return empty("没有可用于归因的板块输入")
        }
        val duplicate = sectors.groupBy { it.key }.filterValues { it.size > 1 }.keys.firstOrNull()
        if (duplicate != null) return empty("板块键重复：$duplicate")
        val incomplete = sectors.firstOrNull { sector ->
            !sector.cashLike && listOf(
                sector.portfolioWeightPct,
                sector.benchmarkWeightPct,
                sector.portfolioReturnPct,
                sector.benchmarkReturnPct
            ).any { it == null || !it.toDouble().isFinite() }
        }
        if (incomplete != null) return empty("板块「${incomplete.name}」缺少权重或收益数据")
        if (sectors.any { it.cashLike && (it.portfolioWeightPct == null || it.benchmarkWeightPct == null) }) {
            return empty("现金桶必须提供组合与基准权重")
        }

        val portfolioWeightSum = sectors.sumOf { it.portfolioWeightPct ?: BigDecimal.ZERO }
        val benchmarkWeightSum = sectors.sumOf { it.benchmarkWeightPct ?: BigDecimal.ZERO }
        if ((portfolioWeightSum - HUNDRED).abs() > WEIGHT_TOLERANCE) {
            return empty("组合权重合计 ${portfolioWeightSum.setScale(2, RoundingMode.HALF_UP)}%，应为 100%")
        }
        if ((benchmarkWeightSum - HUNDRED).abs() > WEIGHT_TOLERANCE) {
            return empty("基准权重合计 ${benchmarkWeightSum.setScale(2, RoundingMode.HALF_UP)}%，应为 100%")
        }

        val benchmarkTotal = sectors.sumOf { sector ->
            (sector.benchmarkWeightPct ?: BigDecimal.ZERO).multiply(sector.benchmarkReturnPct ?: BigDecimal.ZERO)
        }.divide(HUNDRED, 12, RoundingMode.HALF_UP)
        val portfolioTotal = sectors.sumOf { sector ->
            (sector.portfolioWeightPct ?: BigDecimal.ZERO).multiply(sector.portfolioReturnPct ?: BigDecimal.ZERO)
        }.divide(HUNDRED, 12, RoundingMode.HALF_UP)

        val rows = sectors.map { sector ->
            // A cash bucket has a definitional zero return, but its weight difference still carries an
            // allocation effect because the benchmark's other buckets earned a return.
            val portfolioWeight = requireNotNull(sector.portfolioWeightPct)
            val benchmarkWeight = requireNotNull(sector.benchmarkWeightPct)
            val portfolioReturn = if (sector.cashLike) BigDecimal.ZERO else requireNotNull(sector.portfolioReturnPct)
            val benchmarkReturn = if (sector.cashLike) BigDecimal.ZERO else requireNotNull(sector.benchmarkReturnPct)
            val weightDifference = portfolioWeight.subtract(benchmarkWeight)
            val returnDifference = portfolioReturn.subtract(benchmarkReturn)
            val allocation = weightDifference.multiply(benchmarkReturn.subtract(benchmarkTotal))
                .divide(HUNDRED, SCALE, RoundingMode.HALF_UP)
            val selection = benchmarkWeight.multiply(returnDifference)
                .divide(HUNDRED, SCALE, RoundingMode.HALF_UP)
            val interaction = weightDifference.multiply(returnDifference)
                .divide(HUNDRED, SCALE, RoundingMode.HALF_UP)
            BrinsonSectorResult(
                key = sector.key,
                name = sector.name,
                portfolioWeightPct = portfolioWeight.setScale(2, RoundingMode.HALF_UP),
                benchmarkWeightPct = benchmarkWeight.setScale(2, RoundingMode.HALF_UP),
                allocationPct = allocation,
                selectionPct = selection,
                interactionPct = interaction,
                totalPct = allocation.add(selection).add(interaction).setScale(SCALE, RoundingMode.HALF_UP)
            )
        }

        val allocation = rows.sumOf { it.allocationPct ?: BigDecimal.ZERO }
        val selection = rows.sumOf { it.selectionPct ?: BigDecimal.ZERO }
        val interaction = rows.sumOf { it.interactionPct ?: BigDecimal.ZERO }
        val total = allocation.add(selection).add(interaction)
        val activeReturn = portfolioTotal.subtract(benchmarkTotal)
        val reconciled = (total.subtract(activeReturn)).abs() <= BigDecimal("0.02")
        return BrinsonAttributionResult(
            sectors = rows,
            benchmarkReturnPct = benchmarkTotal.setScale(SCALE, RoundingMode.HALF_UP),
            portfolioReturnPct = portfolioTotal.setScale(SCALE, RoundingMode.HALF_UP),
            allocationPct = allocation.setScale(SCALE, RoundingMode.HALF_UP),
            selectionPct = selection.setScale(SCALE, RoundingMode.HALF_UP),
            interactionPct = interaction.setScale(SCALE, RoundingMode.HALF_UP),
            totalPct = total.setScale(SCALE, RoundingMode.HALF_UP),
            reconciled = reconciled,
            missingReason = if (reconciled) null else "归因合计与主动收益差异超过 0.02 个百分点，请检查权重口径"
        )
    }

    private fun empty(reason: String) = BrinsonAttributionResult(
        sectors = emptyList(),
        benchmarkReturnPct = null,
        portfolioReturnPct = null,
        allocationPct = null,
        selectionPct = null,
        interactionPct = null,
        totalPct = null,
        reconciled = false,
        missingReason = reason
    )
}
