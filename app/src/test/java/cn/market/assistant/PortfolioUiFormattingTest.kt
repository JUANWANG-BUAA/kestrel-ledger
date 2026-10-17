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
    fun groupedMoneyAddsThousandsSeparators() {
        assertEquals("1,000.00", BigDecimal("1000").portfolioMoneyGrouped())
        assertEquals("-12,345.68", BigDecimal("-12345.678").portfolioMoneyGrouped())
        assertEquals("0.10", BigDecimal("0.1").portfolioMoneyGrouped())
    }

    @Test
    fun quantityStripsInsignificantZerosWithoutLosingPrecision() {
        assertEquals("100", BigDecimal("100.0000").portfolioQty())
        assertEquals("0.015", BigDecimal("0.0150").portfolioQty())
        assertEquals("1234.5678", BigDecimal("1234.5678").portfolioQty())
    }

    @Test
    fun displayQuantityCapsAtFourDecimalsAndGroups() {
        assertEquals("3,530.5748", BigDecimal("3530.57477757").portfolioQtyDisplay())
        assertEquals("100", BigDecimal("100.0000").portfolioQtyDisplay())
        assertEquals("0.015", BigDecimal("0.0150").portfolioQtyDisplay())
    }
}
