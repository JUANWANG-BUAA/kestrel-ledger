package cn.market.assistant

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class PortfolioUiFormattingTest {
    @Test
    fun moneyKeepsTwoDecimalsEvenForTrailingZeros() {
        assertEquals("1000.00", BigDecimal("1000").portfolioMoney())
        assertEquals("-12.35", BigDecimal("-12.345").portfolioMoney())
        assertEquals("0.10", BigDecimal("0.1").portfolioMoney())
    }

    @Test
    fun quantityStripsInsignificantZerosWithoutLosingPrecision() {
        assertEquals("100", BigDecimal("100.0000").portfolioQty())
        assertEquals("0.015", BigDecimal("0.0150").portfolioQty())
        assertEquals("1234.5678", BigDecimal("1234.5678").portfolioQty())
    }
}
