package cn.market.assistant.domain.portfolio

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XirrCalculatorTest {
    @Test
    fun oneYearReturnMatchesTenPercent() {
        val result = calculate(listOf(flow("2025-01-01", "-1000"), flow("2026-01-01", "1100")))

        assertRate(0.10, result, 1e-4)
    }

    @Test
    fun fiveDatedFlowsMatchTheKnownAnnualRate() {
        val start = LocalDate.parse("2024-01-01")
        val dates = listOf(start, start.plusDays(90), start.plusDays(240), start.plusDays(500), start.plusDays(730))
        val intermediate = listOf(200.0, 300.0, 100.0)
        val finalValue = (1_000.0 - intermediate.indices.sumOf { index ->
            intermediate[index] / 1.18.pow(dates[index + 1].toEpochDay() - start.toEpochDay())
        }) * 1.18.pow(2.0)
        val flows = listOf(flow(dates[0], "-1000")) + intermediate.mapIndexed { index, amount ->
            flow(dates[index + 1], amount.toString())
        } + flow(dates.last(), finalValue.toString())

        assertRate(0.18, XirrCalculator.calculate(flows), 1e-6)
    }

    @Test
    fun sameDayFlowsAreCombinedBeforeSolving() {
        val result = calculate(listOf(
            flow("2025-01-01", "-600"), flow("2025-01-01", "-400"), flow("2026-01-01", "1100")
        ))

        assertRate(0.10, result, 1e-4)
    }

    @Test
    fun reportsMissingSignsAndMultipleRootsInsteadOfInventingARate() {
        val positiveOnly = calculate(listOf(flow("2025-01-01", "100"), flow("2026-01-01", "200")))
        val negativeOnly = calculate(listOf(flow("2025-01-01", "-100"), flow("2026-01-01", "-200")))
        val multipleRoots = calculate(listOf(
            flow("2025-01-01", "-100"), flow("2026-01-01", "230"), flow("2027-01-01", "-132")
        ))

        assertTrue(positiveOnly is XirrResult.Unavailable && positiveOnly.reason == XirrFailureReason.MISSING_NEGATIVE_FLOW)
        assertTrue(negativeOnly is XirrResult.Unavailable && negativeOnly.reason == XirrFailureReason.MISSING_POSITIVE_FLOW)
        assertTrue(multipleRoots is XirrResult.Unavailable && multipleRoots.reason == XirrFailureReason.MULTIPLE_ROOTS)
    }

    @Test
    fun solvesNearNegativeOneAndAtTenThousandPercent() {
        assertRate(-0.99, calculate(listOf(flow("2025-01-01", "-1000"), flow("2026-01-01", "10"))), 1e-6)
        assertRate(100.0, calculate(listOf(flow("2025-01-01", "-1000"), flow("2026-01-01", "101000"))), 1e-6)
    }

    private fun flow(date: String, amount: String) = flow(LocalDate.parse(date), amount)
    private fun flow(date: LocalDate, amount: String) = CashFlow(date, BigDecimal(amount))
    private fun calculate(flows: List<CashFlow>) = XirrCalculator.calculate(flows)

    private fun assertRate(expected: Double, result: XirrResult, tolerance: Double) {
        assertTrue("Expected a result, got $result", result is XirrResult.Available)
        assertEquals(expected, (result as XirrResult.Available).annualizedRate, tolerance)
    }

    private fun Double.pow(days: Long) = Math.pow(this, days / 365.0)
    private fun Double.pow(years: Double) = Math.pow(this, years)
}
