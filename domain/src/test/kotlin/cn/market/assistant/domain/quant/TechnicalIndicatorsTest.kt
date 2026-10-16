package cn.market.assistant.domain.quant

import cn.market.assistant.domain.market.MarketOhlcBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TechnicalIndicatorsTest {
    @Test
    fun rsiDistinguishesFlatRisingAndFallingWindows() {
        val flat = bars(listOf(10.0, 10.0, 10.0))
        val rising = bars(listOf(10.0, 11.0, 12.0))
        val falling = bars(listOf(12.0, 11.0, 10.0))

        assertNull(MarketIndicators.rsi(flat, period = 2)[1])
        assertEquals(50.0, MarketIndicators.rsi(flat, period = 2)[2]!!, 1e-9)
        assertEquals(100.0, MarketIndicators.rsi(rising, period = 2)[2]!!, 1e-9)
        assertEquals(0.0, MarketIndicators.rsi(falling, period = 2)[2]!!, 1e-9)
    }

    @Test
    fun rsiUsesWilderSmoothingAfterTheInitialWindow() {
        val result = MarketIndicators.rsi(bars(listOf(10.0, 11.0, 12.0, 11.0, 12.0)), period = 2)

        assertEquals(50.0, result[3]!!, 1e-9)
        assertEquals(75.0, result[4]!!, 1e-9)
    }

    @Test
    fun macdIsZeroForAFlatSeriesAndValidatesPeriods() {
        val result = MarketIndicators.macd(bars(List(5) { 7.0 }), fast = 2, slow = 4, signal = 2)

        assertEquals(5, result.size)
        assertEquals(0.0, result.last().dif, 1e-9)
        assertEquals(0.0, result.last().dea, 1e-9)
        assertEquals(0.0, result.last().histogram, 1e-9)
        assertThrows(IllegalArgumentException::class.java) {
            MarketIndicators.macd(bars(listOf(1.0)), fast = 4, slow = 2)
        }
    }

    private fun bars(closes: List<Double>) = closes.mapIndexed { index, close ->
        MarketOhlcBar(index.toString(), close, close, close, close)
    }
}
