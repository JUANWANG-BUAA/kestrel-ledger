package cn.market.assistant.domain.fund

import cn.market.assistant.domain.market.MarketOhlcBar
import cn.market.assistant.domain.market.MarketQuote
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FundNavEstimatorRouteTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val startDay = LocalDate.parse("2026-01-05")
    private val dayCount = 110
    private val days = (0 until dayCount).map { startDay.plusDays(it.toLong()).toString() }
    private val quoteDay = startDay.plusDays(dayCount.toLong())
    private val nowSeconds = quoteDay.atTime(15, 30).atZone(zone).toEpochSecond()

    /** Deterministic benchmark returns in percent, one for every index after the first day. */
    private val benchmarkReturns: Map<String, Double> = buildMap {
        (1 until dayCount).forEach { index -> put(days[index], sin(index * 0.7)) }
    }
    private val bars: List<MarketOhlcBar> = buildList {
        var close = 100.0
        add(MarketOhlcBar(days[0], close, close, close, close, 0.0))
        (1 until dayCount).forEach { index ->
            val open = close
            close *= 1.0 + benchmarkReturns.getValue(days[index]) / 100.0
            add(MarketOhlcBar(days[index], open, close, close, open, 0.0))
        }
    }

    private fun navPoints(changeByIndex: (Int) -> Double) = days.mapIndexed { index, day ->
        FundNavPoint(day, 1.0 + index * 0.0001, null, changeByIndex(index))
    }

    private fun profile(
        name: String,
        fundType: String,
        navHistory: List<FundNavPoint>,
        trackingIndexSecid: String? = null
    ) = FundProfile(
        code = "000001",
        name = name,
        fundType = fundType,
        managementCompany = null,
        unitNav = 1.1,
        accumulatedNav = null,
        changePct = 0.0,
        navDate = days.last(),
        subscriptionStatus = null,
        redemptionStatus = null,
        riskLevel = null,
        navHistory = navHistory,
        holdings = emptyList(),
        holdingsReportDate = null,
        source = "test",
        fetchedAt = nowSeconds,
        trackingIndexSecid = trackingIndexSecid
    )

    private fun quote(secid: String, changePct: Double): MarketQuote {
        val quoteEpoch = quoteDay.atTime(15, 0).atZone(zone).toEpochSecond()
        return MarketQuote(secid, "基准", 100.0, 0.0, changePct, quoteEpoch, quoteEpoch + 60, "test")
    }

    private fun benchmarkAligned(beta: Double) = navPoints { index ->
        (benchmarkReturns[days[index]] ?: 0.0) * beta
    }

    private fun lagOneAligned(beta: Double) = navPoints { index ->
        if (index == 0) 0.0 else (benchmarkReturns[days[index - 1]] ?: 0.0) * beta
    }

    @Test
    fun bondFundFallsBackToThePublishedTreasuryIndex() {
        val estimate = FundNavEstimator.estimate(
            profile("稳健纯债债券A", "债券型-长债", benchmarkAligned(0.8)),
            bars,
            quote("1.000012", 1.0),
            now = nowSeconds
        )

        assertNull(estimate.reason)
        assertEquals(FundEstimateMethod.BOND_INDEX_OLS, estimate.method)
        assertEquals(FundEstimateGrade.HIGH, estimate.grade)
        assertEquals("v2:bond:1.000012", estimate.calibrationRouteKey)
        assertEquals("上证国债指数", estimate.trackingIndexName)
        assertEquals(0.8, estimate.estimatedChangePct!!, 1e-6)
        assertEquals(20, estimate.validationCount)
    }

    @Test
    fun creditBondFundUsesTheCorporateBondIndex() {
        val estimate = FundNavEstimator.estimate(
            profile("中高等级信用债债券A", "债券型-长债", benchmarkAligned(0.6)),
            bars,
            quote("1.000013", 1.0),
            now = nowSeconds
        )

        assertEquals(FundEstimateMethod.BOND_INDEX_OLS, estimate.method)
        assertEquals("v2:bond:1.000013", estimate.calibrationRouteKey)
        assertEquals("上证企债指数", estimate.trackingIndexName)
    }

    @Test
    fun convertibleFundUsesTheConvertibleIndexWithoutGradeCap() {
        val estimate = FundNavEstimator.estimate(
            profile("可转债债券A", "债券型-混合二级", benchmarkAligned(1.0)),
            bars,
            quote("1.000832", 1.0),
            now = nowSeconds
        )

        assertEquals(FundEstimateMethod.CONVERTIBLE_INDEX_OLS, estimate.method)
        assertEquals(FundEstimateGrade.HIGH, estimate.grade)
        assertEquals("v2:convertible:1.000832", estimate.calibrationRouteKey)
        assertEquals("中证转债", estimate.trackingIndexName)
    }

    @Test
    fun mixedBondFundIsCappedAtLowQualityWithAnExplicitReason() {
        val estimate = FundNavEstimator.estimate(
            profile("稳健增利债券A", "债券型-混合二级", benchmarkAligned(0.9)),
            bars,
            quote("1.000012", 1.0),
            now = nowSeconds
        )

        assertEquals(FundEstimateMethod.BOND_INDEX_OLS, estimate.method)
        assertEquals(FundEstimateGrade.LOW, estimate.grade)
        assertTrue(estimate.reason!!.contains("含权部分未建模"))
    }

    @Test
    fun qdiiFundAlignsTheBenchmarkWithOneSessionLagAndStaysLowQuality() {
        val estimate = FundNavEstimator.estimate(
            profile("标普500指数(QDII)", "QDII-指数", lagOneAligned(1.0), trackingIndexSecid = "100.SPX"),
            bars,
            quote("100.SPX", 1.0),
            now = nowSeconds
        )

        assertEquals(FundEstimateMethod.QDII_INDEX_OLS, estimate.method)
        assertEquals(FundEstimateGrade.LOW, estimate.grade)
        assertEquals("v2:qdii:100.SPX", estimate.calibrationRouteKey)
        assertTrue(estimate.reason!!.contains("T-1"))
        assertEquals(1.0, estimate.estimatedChangePct!!, 1e-6)
    }

    @Test
    fun qdiiWithoutABenchmarkRefusesToShiftAnUncalibratedIndex() {
        val estimate = FundNavEstimator.estimate(
            profile("全球科技(QDII)", "QDII-股票", lagOneAligned(1.0)),
            bars,
            null,
            now = nowSeconds
        )

        assertNull(estimate.estimatedChangePct)
        assertEquals(FundEstimateGrade.UNAVAILABLE, estimate.grade)
        assertTrue(estimate.reason!!.contains("缺少可验证的海外基准指数"))
    }

    @Test
    fun shortDurationAndMoneyFundsNeverEmitANumber() {
        val shortDuration = FundNavEstimator.estimate(
            profile("短债债券A", "债券型-中短债", benchmarkAligned(0.5)),
            bars,
            quote("1.000012", 1.0),
            now = nowSeconds
        )
        val certificateOfDeposit = FundNavEstimator.estimate(
            profile("同业存单指数7天持有", "指数型-固收", benchmarkAligned(0.5)),
            bars,
            quote("1.000012", 1.0),
            now = nowSeconds
        )

        assertEquals(FundEstimateGrade.NONE, shortDuration.grade)
        assertEquals(FundEstimateMethod.NONE, shortDuration.method)
        assertEquals(FundEstimateGrade.NONE, certificateOfDeposit.grade)
        assertNull(shortDuration.estimatedChangePct)
    }

    @Test
    fun routeClassificationKeepsDisclosedHoldingsForEquityFundsOnly() {
        assertTrue(FundNavEstimator.usesDisclosedHoldingsRoute(profile("偏股混合A", "混合型-偏股", emptyList())))
        assertFalse(FundNavEstimator.usesDisclosedHoldingsRoute(profile("稳健增利债券A", "债券型-混合二级", emptyList())))
        assertTrue(FundNavEstimator.usesBenchmarkQuoteRoute(profile("稳健纯债债券A", "债券型-长债", emptyList())))
        assertTrue(FundNavEstimator.usesBenchmarkQuoteRoute(profile("可转债债券A", "债券型-混合二级", emptyList())))
        assertFalse(FundNavEstimator.usesBenchmarkQuoteRoute(profile("偏股混合A", "混合型-偏股", emptyList())))
        assertTrue(
            FundNavEstimator.usesBenchmarkQuoteRoute(
                profile("标普500指数(QDII)", "QDII-指数", emptyList(), trackingIndexSecid = "100.SPX")
            )
        )
        assertFalse(FundNavEstimator.usesBenchmarkQuoteRoute(profile("全球科技(QDII)", "QDII-股票", emptyList())))
    }
}
