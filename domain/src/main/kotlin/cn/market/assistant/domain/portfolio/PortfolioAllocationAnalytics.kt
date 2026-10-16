package cn.market.assistant.domain.portfolio

import java.math.BigDecimal
import java.math.RoundingMode

data class PortfolioAllocationResult(
    val closing: List<PortfolioAllocation> = emptyList(),
    val changes: List<PortfolioAllocationChange> = emptyList(),
    val hhi: BigDecimal? = null,
    val effectiveAssetCount: BigDecimal? = null,
    val missingReason: String? = null
)

/** Compares full-account allocation only when both snapshots reconcile to holdings plus cash. */
object PortfolioAllocationAnalytics {
    private val CENT = BigDecimal("0.01")

    fun compare(
        opening: PortfolioSnapshot?,
        closing: PortfolioSnapshot?,
        positionsByDay: Map<java.time.LocalDate, List<PositionSnapshot>>
    ): PortfolioAllocationResult {
        val missing = when {
            opening == null -> "缺少配置变化的期初快照"
            closing == null -> "缺少配置变化的期末快照"
            !opening.accountComplete || !closing.accountComplete -> "账户配置需要完整持仓和现金快照"
            !opening.valuationFresh || !closing.valuationFresh -> "账户配置边界报价已过期或不完整"
            closing.day <= opening.day -> "账户配置变化需要两个不同日期的快照"
            else -> null
        }
        if (missing != null) return PortfolioAllocationResult(missingReason = missing)

        val start = requireNotNull(opening)
        val end = requireNotNull(closing)
        val before = allocationAt(start, positionsByDay[start.day])
            ?: return PortfolioAllocationResult(missingReason = "期初持仓快照与账户总资产无法对账")
        val after = allocationAt(end, positionsByDay[end.day])
            ?: return PortfolioAllocationResult(missingReason = "期末持仓快照与账户总资产无法对账")
        val oldWeights = before.allocations.associateBy { it.id }
        val newWeights = after.allocations.associateBy { it.id }
        val changes = (oldWeights.keys + newWeights.keys).map { id ->
            val old = oldWeights[id]
            val new = newWeights[id]
            PortfolioAllocationChange(
                id = id,
                name = new?.name ?: requireNotNull(old).name,
                changePercentagePoints = (new?.weightPct ?: BigDecimal.ZERO)
                    .subtract(old?.weightPct ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
            )
        }.sortedByDescending { it.changePercentagePoints.abs() }
        return PortfolioAllocationResult(after.allocations, changes, after.hhi, after.effectiveAssetCount)
    }

    private fun allocationAt(
        snapshot: PortfolioSnapshot,
        rows: List<PositionSnapshot>?
    ): AtDate? {
        val positions = rows ?: return null
        if (positions.size != snapshot.positionCount || positions.map { it.asset.symbol }.distinct().size != positions.size) return null
        if (positions.any { it.asset.symbol == CASH_ID }) return null
        if (positions.any { it.marketValue == null || it.marketValue.signum() < 0 }) return null
        val cash = snapshot.cashBalance?.takeIf { it.signum() >= 0 } ?: return null
        val total = snapshot.totalValue?.takeIf { it.signum() > 0 } ?: return null
        val holdingsTotal = positions.fold(BigDecimal.ZERO) { sum, position -> sum + requireNotNull(position.marketValue) }
        val recordedHoldings = snapshot.marketValue ?: return null
        if (holdingsTotal.subtract(recordedHoldings).abs() > CENT) return null
        val components = positions.map { position ->
            Component(position.asset.symbol, position.asset.name,
                if (position.asset.kind == AssetKind.GOLD) AllocationKind.GOLD else AllocationKind.FUND,
                requireNotNull(position.marketValue))
        } + Component(CASH_ID, "账户现金", AllocationKind.CASH, cash)
        val reconstructed = components.fold(BigDecimal.ZERO) { sum, item -> sum + item.value }
        if (reconstructed.subtract(total).abs() > CENT) return null

        val allocations = components.map { component ->
            PortfolioAllocation(component.id, component.name, component.kind, component.value,
                component.value.multiply(HUNDRED).divide(total, 6, RoundingMode.HALF_UP).setScale(4, RoundingMode.HALF_UP))
        }.sortedByDescending { it.weightPct }
        val hhi = components.fold(BigDecimal.ZERO) { sum, component ->
            val weight = component.value.divide(total, 18, RoundingMode.HALF_UP)
            sum + weight.multiply(weight)
        }.setScale(6, RoundingMode.HALF_UP)
        val effectiveCount = if (hhi.signum() == 0) null else BigDecimal.ONE.divide(hhi, 2, RoundingMode.HALF_UP)
        return AtDate(allocations, hhi, effectiveCount)
    }

    private data class Component(val id: String, val name: String, val kind: AllocationKind, val value: BigDecimal)
    private data class AtDate(
        val allocations: List<PortfolioAllocation>,
        val hhi: BigDecimal,
        val effectiveAssetCount: BigDecimal?
    )

    private const val CASH_ID = "ACCOUNT_CASH"
    private val HUNDRED = BigDecimal("100")
}
