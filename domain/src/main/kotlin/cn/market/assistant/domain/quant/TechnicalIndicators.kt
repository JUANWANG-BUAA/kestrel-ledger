package cn.market.assistant.domain.quant

import cn.market.assistant.domain.market.MarketOhlcBar

data class MacdValue(val dif: Double, val dea: Double, val histogram: Double)

/** Deterministic close-price indicators. RSI uses Wilder smoothing; MACD uses recursive EMAs. */
object MarketIndicators {
    fun rsi(bars: List<MarketOhlcBar>, period: Int = 14): List<Double?> {
        require(period > 0) { "RSI 周期必须大于 0" }
        val values = MutableList<Double?>(bars.size) { null }
        if (bars.size <= period) return values

        var averageGain = 0.0
        var averageLoss = 0.0
        for (index in 1..period) {
            val change = bars[index].close - bars[index - 1].close
            if (change > 0) averageGain += change else averageLoss -= change
        }
        averageGain /= period
        averageLoss /= period
        values[period] = rsiValue(averageGain, averageLoss)

        for (index in period + 1 until bars.size) {
            val change = bars[index].close - bars[index - 1].close
            val gain = change.coerceAtLeast(0.0)
            val loss = (-change).coerceAtLeast(0.0)
            averageGain = ((averageGain * (period - 1)) + gain) / period
            averageLoss = ((averageLoss * (period - 1)) + loss) / period
            values[index] = rsiValue(averageGain, averageLoss)
        }
        return values
    }

    fun macd(bars: List<MarketOhlcBar>, fast: Int = 12, slow: Int = 26, signal: Int = 9): List<MacdValue> {
        require(fast > 0 && slow > 0 && signal > 0) { "MACD 周期必须大于 0" }
        require(fast < slow) { "MACD 快线周期必须小于慢线周期" }
        if (bars.isEmpty()) return emptyList()
        val closes = bars.map { it.close }
        val fastEma = ema(closes, fast)
        val slowEma = ema(closes, slow)
        val dif = closes.indices.map { fastEma[it] - slowEma[it] }
        val dea = ema(dif, signal)
        return dif.indices.map { MacdValue(dif[it], dea[it], (dif[it] - dea[it]) * 2.0) }
    }

    private fun ema(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val alpha = 2.0 / (period + 1.0)
        return buildList {
            var current = values.first()
            add(current)
            values.drop(1).forEach { value ->
                current = alpha * value + (1.0 - alpha) * current
                add(current)
            }
        }
    }

    private fun rsiValue(averageGain: Double, averageLoss: Double): Double = when {
        averageGain == 0.0 && averageLoss == 0.0 -> 50.0
        averageLoss == 0.0 -> 100.0
        averageGain == 0.0 -> 0.0
        else -> 100.0 - 100.0 / (1.0 + averageGain / averageLoss)
    }
}
