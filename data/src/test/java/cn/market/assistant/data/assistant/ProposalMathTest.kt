package cn.market.assistant.data.assistant

import java.math.BigDecimal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProposalMathTest {
    private fun d(value: String) = BigDecimal(value)

    @Test
    fun buyRequiresAmountToIncludeFeeAndUnits() {
        assertTrue(isConsistentTradeProposal(d("100.50"), d("100"), d("1.00"), d("0.50"), buy = true))
        assertFalse(isConsistentTradeProposal(d("100.00"), d("100"), d("1.00"), d("0.50"), buy = true))
    }

    @Test
    fun sellUsesGrossProceedsWithoutFee() {
        assertTrue(isConsistentTradeProposal(d("100.00"), d("100"), d("1.00"), d("0.50"), buy = false))
        assertFalse(isConsistentTradeProposal(d("99.00"), d("100"), d("1.00"), d("0.50"), buy = false))
    }

    @Test
    fun rejectsNonPositiveValues() {
        assertFalse(isConsistentTradeProposal(d("0"), d("1"), d("1"), d("0"), buy = true))
        assertFalse(isConsistentTradeProposal(d("1"), d("1"), d("1"), d("-0.1"), buy = true))
    }
}
