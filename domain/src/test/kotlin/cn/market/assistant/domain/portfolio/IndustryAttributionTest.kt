package cn.market.assistant.domain.portfolio

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndustryAttributionTest {
    private val benchmarkWeights = mapOf("科技" to BigDecimal("50"), "金融" to BigDecimal("50"))
    private val benchmarkReturns = mapOf("科技" to BigDecimal("2"), "金融" to BigDecimal("0"))

    @Test
    fun mapsHoldingsIntoIndustriesAndDelegatesToBrinson() {
        val result = IndustryAttribution.attribute(
            holdings = listOf(
                holding("科技A", "600", "科技", "4"),
                holding("金融A", "400", "金融", "-1")
            ),
            benchmarkWeightsPct = benchmarkWeights,
            benchmarkReturnsPct = benchmarkReturns
        )

        assertNull(result.missingReason)
        assertEquals(BigDecimal("100.00"), result.coveragePct)
        val attribution = requireNotNull(result.attribution)
        assertTrue(attribution.reconciled)
        assertEquals(BigDecimal("0.2000"), attribution.allocationPct)
        assertEquals(BigDecimal("0.5000"), attribution.selectionPct)
        assertEquals(BigDecimal("1.0000"), attribution.totalPct)
    }

    @Test
    fun lowCoverageRefusesToAttributeAndNamesTheUnmappedHolding() {
        val result = IndustryAttribution.attribute(
            holdings = listOf(
                holding("科技A", "600", "科技", "4"),
                holding("未知标的", "400", null, "1")
            ),
            benchmarkWeightsPct = benchmarkWeights,
            benchmarkReturnsPct = benchmarkReturns
        )

        assertEquals(BigDecimal("60.00"), result.coveragePct)
        assertNull(result.attribution)
        assertTrue(result.missingReason!!.contains("未知标的"))
        assertEquals(listOf("未知标的"), result.unmappedAssetNames)
    }

    @Test
    fun industryMissingFromTheBenchmarkIsReportedInsteadOfGuessed() {
        val result = IndustryAttribution.attribute(
            holdings = listOf(holding("医药A", "1000", "医药", "1")),
            benchmarkWeightsPct = mapOf("科技" to BigDecimal("100")),
            benchmarkReturnsPct = mapOf("科技" to BigDecimal("2"))
        )

        assertNull(result.attribution)
        assertTrue(result.missingReason!!.contains("医药"))
    }

    @Test
    fun unmappedResidualIsCarriedAsAnExplicitCashBucket() {
        val result = IndustryAttribution.attribute(
            holdings = listOf(
                holding("科技A", "900", "科技", "4"),
                holding("小额未知", "100", null, "1")
            ),
            benchmarkWeightsPct = mapOf("科技" to BigDecimal("100")),
            benchmarkReturnsPct = mapOf("科技" to BigDecimal("2")),
            minimumCoveragePct = BigDecimal("80")
        )

        assertNull(result.missingReason)
        val attribution = requireNotNull(result.attribution)
        val cash = attribution.sectors.firstOrNull { it.key == "cash" }
        assertNotNull("未映射权重必须显式出现在现金桶", cash)
        assertEquals(BigDecimal("10.00"), cash!!.portfolioWeightPct)
    }

    private fun holding(name: String, value: String, bucket: String?, periodReturn: String?) = IndustryHoldingInput(
        assetId = name,
        assetName = name,
        marketValue = BigDecimal(value),
        bucket = bucket,
        periodReturnPct = periodReturn?.let(::BigDecimal)
    )
}
