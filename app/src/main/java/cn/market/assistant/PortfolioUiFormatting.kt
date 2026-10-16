package cn.market.assistant

import java.math.BigDecimal
import java.math.RoundingMode

internal fun BigDecimal.portfolioMoney(): String = setScale(2, RoundingMode.HALF_UP).toPlainString()

internal fun BigDecimal.portfolioQty(): String = stripTrailingZeros().toPlainString()
