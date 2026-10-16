package cn.market.assistant.domain.fund

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

enum class GoldPremiumConfidence { CALIBRATED, INDICATIVE, UNAVAILABLE }

/** One aligned daily observation of the domestic and international gold quotes. */
data class GoldPremiumObservation(
    val day: String,
    /** Domestic quote in CNY per gram, e.g. SGE Au99.99 or SHAU. */
    val domesticCnyPerGram: Double,
    /** International quote in USD per troy ounce, e.g. XAU/USD. */
    val internationalUsdPerOunce: Double,
    /** USD/CNY at the same observation time. */
    val usdCny: Double
)

data class GoldPremiumFit(
    val observationCount: Int,
    val windowDays: Int,
    val averagePremiumPct: Double?,
    val medianPremiumPct: Double?,
    val premiumStandardDeviationPct: Double?,
    val residualMaePct: Double?,
    val residualP90Pct: Double?,
    val estimatedDomesticCnyPerGram: Double?,
    val confidence: GoldPremiumConfidence,
    /**
     * Always false. The research pipeline measured a conversion error of CNY 3.4~4.8 while the bank
     * spread itself is CNY 0.5~3, so a converted international quote can only be a fallback reference
     * and must never be used to derive a bank quote or its spread.
     */
    val bankQuoteEligible: Boolean = false,
    val reason: String?
)

/**
 * Converts the international gold quote into a domestic CNY-per-gram reference with a rolling
 * premium correction. The model measures the conversion error honestly and refuses to claim bank
 * quote accuracy.
 */
object GoldPremiumModel {
    const val GRAMS_PER_TROY_OUNCE = 31.1034768
    const val DEFAULT_WINDOW_DAYS = 60
    const val DEFAULT_MIN_OBSERVATIONS = 20
    const val MAX_CALIBRATED_MAE_PCT = 0.5

    fun fairValueCnyPerGram(internationalUsdPerOunce: Double, usdCny: Double): Double? {
        if (!internationalUsdPerOunce.isFinite() || !usdCny.isFinite() ||
            internationalUsdPerOunce <= 0.0 || usdCny <= 0.0
        ) return null
        return internationalUsdPerOunce * usdCny / GRAMS_PER_TROY_OUNCE
    }

    fun premiumPct(domesticCnyPerGram: Double, fairValueCnyPerGram: Double?): Double? {
        val fair = fairValueCnyPerGram ?: return null
        if (!domesticCnyPerGram.isFinite() || domesticCnyPerGram <= 0.0 || fair <= 0.0) return null
        return (domesticCnyPerGram / fair - 1.0) * 100.0
    }

    /**
     * Fits the rolling premium window and estimates today's domestic reference value.
     *
     * @param currentInternationalUsdPerOunce latest international quote
     * @param currentUsdCny latest FX rate
     */
    fun fit(
        observations: List<GoldPremiumObservation>,
        currentInternationalUsdPerOunce: Double? = null,
        currentUsdCny: Double? = null,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
        minimumObservations: Int = DEFAULT_MIN_OBSERVATIONS
    ): GoldPremiumFit {
        require(windowDays > 0) { "滚动窗口必须为正数" }
        require(minimumObservations >= 2) { "最小样本数必须不少于 2" }
        val ordered = observations.asSequence()
            .filter { observation ->
                runCatching { LocalDate.parse(observation.day) }.isSuccess &&
                    fairValueCnyPerGram(observation.internationalUsdPerOunce, observation.usdCny) != null &&
                    observation.domesticCnyPerGram.isFinite() && observation.domesticCnyPerGram > 0.0
            }
            .distinctBy { it.day }
            .sortedBy { it.day }
            .toList()
        if (ordered.size < minimumObservations) {
            return unavailable("国际金价与国内金价对齐样本 ${ordered.size} 个，少于 $minimumObservations 个", ordered.size)
        }

        val window = ordered.takeLast(windowDays)
        val premiums = window.mapNotNull { observation ->
            premiumPct(observation.domesticCnyPerGram, fairValueCnyPerGram(observation.internationalUsdPerOunce, observation.usdCny))
        }.filter { it.isFinite() }
        if (premiums.size < minimumObservations) {
            return unavailable("有效溢价样本 ${premiums.size} 个，少于 $minimumObservations 个", premiums.size)
        }
        val averagePremium = premiums.average()
        val sortedPremiums = premiums.sorted()
        val medianPremium = if (sortedPremiums.size % 2 == 1) {
            sortedPremiums[sortedPremiums.size / 2]
        } else {
            (sortedPremiums[sortedPremiums.size / 2 - 1] + sortedPremiums[sortedPremiums.size / 2]) / 2.0
        }
        val standardDeviation = if (premiums.size > 1) {
            kotlin.math.sqrt(premiums.sumOf { (it - averagePremium) * (it - averagePremium) } / (premiums.size - 1))
        } else {
            null
        }

        // Walk the window with a trailing mean so the reported error is a genuine out-of-sample number.
        val trailingErrors = mutableListOf<Double>()
        for (index in 1 until window.size) {
            val trainingPremiums = window.take(index).mapNotNull { observation ->
                premiumPct(observation.domesticCnyPerGram, fairValueCnyPerGram(observation.internationalUsdPerOunce, observation.usdCny))
            }
            if (trainingPremiums.size < minimumObservations) continue
            val trainingMean = trainingPremiums.average()
            val actual = window[index]
            val fair = fairValueCnyPerGram(actual.internationalUsdPerOunce, actual.usdCny) ?: continue
            val predicted = fair * (1.0 + trainingMean / 100.0)
            val error = abs(predicted / actual.domesticCnyPerGram - 1.0) * 100.0
            if (error.isFinite()) trailingErrors += error
        }
        val residualMae = trailingErrors.takeIf { it.isNotEmpty() }?.average()
        val residualP90 = trailingErrors.takeIf { it.isNotEmpty() }?.sorted()
            ?.let { errors -> errors[(max(1L, (errors.size * 0.9).toLong()).toInt() - 1).coerceIn(0, errors.lastIndex)] }

        val fairCurrent = if (currentInternationalUsdPerOunce != null && currentUsdCny != null) {
            fairValueCnyPerGram(currentInternationalUsdPerOunce, currentUsdCny)
        } else {
            null
        }
        val estimated = fairCurrent?.let { fair -> fair * (1.0 + averagePremium / 100.0) }
        val confidence = when {
            estimated == null -> GoldPremiumConfidence.INDICATIVE
            residualMae != null && residualMae <= MAX_CALIBRATED_MAE_PCT -> GoldPremiumConfidence.CALIBRATED
            else -> GoldPremiumConfidence.INDICATIVE
        }
        val reason = when {
            residualMae == null -> "尚无足够的滚动样本外误差，仅作参考"
            residualMae > MAX_CALIBRATED_MAE_PCT ->
                "换算误差 ${formatPct(residualMae)} 高于 ${formatPct(MAX_CALIBRATED_MAE_PCT)} 门槛，仅作参考"
            else -> null
        }
        return GoldPremiumFit(
            observationCount = premiums.size,
            windowDays = window.size,
            averagePremiumPct = averagePremium,
            medianPremiumPct = medianPremium,
            premiumStandardDeviationPct = standardDeviation,
            residualMaePct = residualMae,
            residualP90Pct = residualP90,
            estimatedDomesticCnyPerGram = estimated,
            confidence = confidence,
            bankQuoteEligible = false,
            reason = reason
        )
    }

    private fun formatPct(value: Double): String =
        "${String.format(java.util.Locale.US, "%.3f", value)}%"

    private fun unavailable(reason: String, count: Int) = GoldPremiumFit(
        observationCount = count,
        windowDays = 0,
        averagePremiumPct = null,
        medianPremiumPct = null,
        premiumStandardDeviationPct = null,
        residualMaePct = null,
        residualP90Pct = null,
        estimatedDomesticCnyPerGram = null,
        confidence = GoldPremiumConfidence.UNAVAILABLE,
        bankQuoteEligible = false,
        reason = reason
    )
}
