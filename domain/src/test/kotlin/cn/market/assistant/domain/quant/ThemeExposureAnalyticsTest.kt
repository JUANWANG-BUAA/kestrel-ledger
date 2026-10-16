package cn.market.assistant.domain.quant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeExposureAnalyticsTest {
    private val days = (0 until 100).map { index ->
        java.time.LocalDate.parse("2026-01-01").plusDays(index.toLong()).toString()
    }

    @Test
    fun recoversTheThemeLoadingAndLeavesUnrelatedThemesAtZero() {
        val themeA = days.associateWith { day -> Math.sin(day.hashCode().toDouble()) * 1.5 }
        val themeB = days.associateWith { day -> Math.cos(day.hashCode().toDouble()) * 1.2 }
        val fundReturns = days.associateWith { day -> 0.4 * themeA.getValue(day) }

        val result = ThemeExposureAnalytics.fit(
            fundReturns,
            mapOf("theme-a" to themeA, "theme-b" to themeB),
            mapOf("theme-a" to "主题 A", "theme-b" to "主题 B"),
            minimumObservations = 60,
            alpha = 0.0
        )

        assertNull(result.reason)
        assertEquals(100, result.observationCount)
        assertEquals(100.0, result.coveragePct, 1e-9)
        val exposureA = result.exposures.first { it.code == "theme-a" }
        val exposureB = result.exposures.first { it.code == "theme-b" }
        assertEquals(0.4, exposureA.beta, 1e-6)
        assertEquals(0.0, exposureB.beta, 1e-6)
        assertEquals(1.0, result.rSquared!!, 1e-6)
        assertEquals("主题 A", exposureA.name)
    }

    @Test
    fun negativeThemeLoadingIsClippedToZeroByTheNonNegativeConstraint() {
        val themeA = days.associateWith { day -> Math.sin(day.hashCode().toDouble()) * 1.5 }
        val themeB = days.associateWith { day -> Math.cos(day.hashCode().toDouble()) * 1.2 }
        val fundReturns = days.associateWith { day -> 0.4 * themeA.getValue(day) - 0.3 * themeB.getValue(day) }

        val result = ThemeExposureAnalytics.fit(
            fundReturns,
            mapOf("theme-a" to themeA, "theme-b" to themeB),
            alpha = 0.0
        )

        val exposureA = result.exposures.first { it.code == "theme-a" }
        val exposureB = result.exposures.first { it.code == "theme-b" }
        assertEquals(0.0, exposureB.beta, 1e-9)
        assertEquals(0.4, exposureA.beta, 0.05)
        assertTrue(result.rSquared!! < 1.0)
    }

    @Test
    fun sparseThemeIsRejectedByCoverageBeforeRegression() {
        val themeA = days.take(50).associateWith { 1.0 }
        val fundReturns = days.associateWith { 0.5 }

        val result = ThemeExposureAnalytics.fit(
            fundReturns,
            mapOf("theme-a" to themeA),
            minimumObservations = 60
        )

        assertNotNull(result.reason)
        assertTrue(result.reason!!.contains("覆盖率不足"))
        assertTrue(result.exposures.isEmpty())
    }

    @Test
    fun peerResidualCorrelationFlagsAMissingCommonFactor() {
        val theme = days.associateWith { day -> Math.sin(day.hashCode().toDouble()) * 1.0 }
        val hidden = days.associateWith { day -> Math.cos(day.hashCode().toDouble()) * 0.8 }
        val firstFund = days.associateWith { day -> 0.5 * theme.getValue(day) + hidden.getValue(day) }
        val secondFund = days.associateWith { day -> 0.5 * theme.getValue(day) + hidden.getValue(day) * 1.1 }

        val themes = mapOf("theme" to theme)
        val firstFit = ThemeExposureAnalytics.fit(firstFund, themes, alpha = 0.0)
        val secondFit = ThemeExposureAnalytics.fit(secondFund, themes, alpha = 0.0)
        val correlation = ThemeExposureAnalytics.residualCorrelation(
            ThemeExposureAnalytics.residualSeries(firstFund, themes, firstFit),
            ThemeExposureAnalytics.residualSeries(secondFund, themes, secondFit)
        )

        requireNotNull(correlation)
        assertTrue("同步残差应高度相关：$correlation", correlation > 0.95)
    }

    @Test
    fun insufficientSampleReturnsAReasonInsteadOfAnEstimate() {
        val few = days.take(30).associateWith { 0.1 }
        val result = ThemeExposureAnalytics.fit(few, mapOf("theme" to few))

        assertNotNull(result.reason)
        assertEquals(0, result.observationCount)
    }
}
