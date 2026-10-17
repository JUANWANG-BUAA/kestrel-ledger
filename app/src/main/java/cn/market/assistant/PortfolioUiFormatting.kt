package cn.market.assistant

import java.math.BigDecimal
import java.math.RoundingMode

internal fun BigDecimal.portfolioMoney(): String = setScale(2, RoundingMode.HALF_UP).toPlainString()

internal fun BigDecimal.portfolioQty(): String = stripTrailingZeros().toPlainString()

/** Amounts read best grouped in a finance layout: ¥12,345.67 rather than ¥12345.67. */
internal fun BigDecimal.portfolioMoneyGrouped(): String = groupThousands(setScale(2, RoundingMode.HALF_UP).toPlainString())

/** Holding quantity for display: at most four decimals, grouped, so 3530.57477757 reads as 3,530.5748. */
internal fun BigDecimal.portfolioQtyDisplay(): String {
    val scaled = setScale(4, RoundingMode.HALF_UP).stripTrailingZeros()
    val plain = if (scaled.scale() <= 0) scaled.setScale(0).toPlainString() else scaled.toPlainString()
    return groupThousands(plain)
}

private fun groupThousands(plain: String): String {
    val negative = plain.startsWith("-")
    val body = if (negative) plain.substring(1) else plain
    val dot = body.indexOf('.')
    val integer = if (dot >= 0) body.substring(0, dot) else body
    val fraction = if (dot >= 0) body.substring(dot) else ""
    val grouped = integer.reversed().chunked(3).joinToString(",").reversed()
    return (if (negative) "-" else "") + grouped + fraction
}
