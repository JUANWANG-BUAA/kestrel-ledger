package cn.market.assistant.domain.portfolio

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrinsonAttributionTest {
    @Test
    fun decomposesActiveReturnIntoAllocationSelectionAndInteraction() {
        val sectors = listOf(
            sector("tech", "科技", pw = "60", bw = "50", pr = "4", br = "2"),
            sector("finance", "金融", pw = "40", bw = "50", pr = "-1", br = "0")
        )

        val result = BrinsonAttribution.singlePeriod(sectors)

        assertNull(result.missingReason)
        assertTrue(result.reconciled)
        // 基准 = 0.5*2 + 0.5*0 = 1.0%，组合 = 0.6*4 + 0.4*(-1) = 2.0%
        assertEquals(BigDecimal("1.0000"), result.benchmarkReturnPct)
        assertEquals(BigDecimal("2.0000"), result.portfolioReturnPct)
        assertEquals(BigDecimal("1.0000"), result.totalPct)
        assertEquals(BigDecimal("0.2000"), result.allocationPct)
        assertEquals(BigDecimal("0.5000"), result.selectionPct)
        assertEquals(BigDecimal("0.3000"), result.interactionPct)
        val tech = result.sectors.first { it.key == "tech" }
        assertEquals(BigDecimal("0.1000"), tech.allocationPct)
        assertEquals(BigDecimal("1.0000"), tech.selectionPct)
    }

    @Test
    fun detailedSectorRowsSumToTheTotals() {
        val result = BrinsonAttribution.singlePeriod(
            listOf(
                sector("a", "甲", pw = "70", bw = "40", pr = "3", br = "1"),
                sector("b", "乙", pw = "30", bw = "60", pr = "2", br = "1.5")
            )
        )

        val allocation = result.sectors.sumOf { it.allocationPct ?: BigDecimal.ZERO }
        val selection = result.sectors.sumOf { it.selectionPct ?: BigDecimal.ZERO }
        val interaction = result.sectors.sumOf { it.interactionPct ?: BigDecimal.ZERO }
        assertEquals(result.allocationPct, allocation)
        assertEquals(result.selectionPct, selection)
        assertEquals(result.interactionPct, interaction)
    }

    @Test
    fun cashBucketKeepsTheIdentityWhenThePortfolioIsNotFullyInvested() {
        val result = BrinsonAttribution.singlePeriod(
            listOf(
                sector("equity", "权益", pw = "60", bw = "70", pr = "5", br = "4"),
                BrinsonSectorInput(
                    key = "cash",
                    name = "现金",
                    portfolioWeightPct = BigDecimal("40"),
                    benchmarkWeightPct = BigDecimal("30"),
                    portfolioReturnPct = BigDecimal.ZERO,
                    benchmarkReturnPct = BigDecimal.ZERO,
                    cashLike = true
                )
            )
        )

        assertTrue(result.reconciled)
        assertEquals(BigDecimal("0.2000"), result.totalPct)
        val cash = result.sectors.first { it.key == "cash" }
        // 现金桶收益为零，但低配债券（-10pp）在高收益基准下仍产生负的配置效应。
        assertEquals(BigDecimal("-0.2800"), cash.allocationPct)
        assertEquals(BigDecimal("-0.2800"), cash.totalPct)
    }

    @Test
    fun missingOrUnbalancedInputIsReportedInsteadOfTreatedAsZero() {
        val missing = BrinsonAttribution.singlePeriod(
            listOf(sector("a", "甲", pw = "100", bw = "100", pr = null, br = "1"))
        )
        assertNull(missing.allocationPct)
        assertTrue(missing.missingReason?.contains("缺少权重或收益") == true)

        val unbalanced = BrinsonAttribution.singlePeriod(
            listOf(sector("a", "甲", pw = "80", bw = "100", pr = "1", br = "1"))
        )
        assertFalse(unbalanced.reconciled)
        assertTrue(unbalanced.missingReason?.contains("应为 100%") == true)
    }

    private fun sector(
        key: String,
        name: String,
        pw: String,
        bw: String,
        pr: String?,
        br: String?
    ) = BrinsonSectorInput(
        key = key,
        name = name,
        portfolioWeightPct = BigDecimal(pw),
        benchmarkWeightPct = BigDecimal(bw),
        portfolioReturnPct = pr?.let(::BigDecimal),
        benchmarkReturnPct = br?.let(::BigDecimal)
    )
}
