package cn.market.assistant.data.assistant

import java.math.BigDecimal

/**
 * Local trade proposals must be arithmetically consistent before the user sees a confirmation sheet.
 * A buy amount includes the fee; a sell amount is gross proceeds. Two cents of rounding tolerance keeps
 * fund NAV rounding from rejecting legitimate proposals while still rejecting mismatched numbers.
 */
internal fun isConsistentTradeProposal(
    amount: BigDecimal,
    units: BigDecimal,
    unitPrice: BigDecimal,
    fee: BigDecimal,
    buy: Boolean
): Boolean {
    if (amount <= BigDecimal.ZERO || units <= BigDecimal.ZERO || unitPrice <= BigDecimal.ZERO || fee < BigDecimal.ZERO) {
        return false
    }
    val expected = units.multiply(unitPrice).add(if (buy) fee else BigDecimal.ZERO)
    return amount.subtract(expected).abs() <= BigDecimal("0.02")
}
