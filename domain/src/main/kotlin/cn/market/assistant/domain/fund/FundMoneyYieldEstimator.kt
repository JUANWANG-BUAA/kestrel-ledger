package cn.market.assistant.domain.fund

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Uses published money-fund 10,000-share yields; it never treats them as a changing unit NAV. */
object FundMoneyYieldEstimator {
    private const val HISTORY_WINDOW = 7
    private const val VALIDATION_WINDOW = 20
    private const val MIN_VALIDATION_COUNT = 10
    private const val MAX_REPORT_AGE_DAYS = 14L
    private val marketZone = ZoneId.of("Asia/Shanghai")

    fun estimate(profile: FundProfile, now: Long = System.currentTimeMillis() / 1000): FundMoneyYieldEstimate {
        val today = runCatching { Instant.ofEpochSecond(now).atZone(marketZone).toLocalDate() }
            .getOrElse { LocalDate.now(marketZone) }
        val points = profile.navHistory.mapNotNull { point ->
            val day = runCatching { LocalDate.parse(point.day) }.getOrNull() ?: return@mapNotNull null
            val income = point.unitNav
            if (day.isAfter(today) || !income.isFinite() || income < 0.0) return@mapNotNull null
            day to income
        }.distinctBy { it.first }.sortedBy { it.first }
        val latest = points.lastOrNull()
        val latestIncome = latest?.second
        val latestDay = latest?.first
        val unavailable = { reason: String ->
            FundMoneyYieldEstimate(
                code = profile.code,
                latestIncomePer10k = latestIncome,
                estimatedIncomePer10k = null,
                estimatedDailyReturnPct = null,
                baseDate = latestDay?.toString(),
                grade = FundEstimateGrade.UNAVAILABLE,
                historyCount = points.size,
                validationCount = 0,
                validationMaePer10k = null,
                persistenceBaselineMaePer10k = null,
                source = profile.source,
                fetchedAt = profile.fetchedAt,
                reason = reason
            )
        }
        if (!profile.fundType.orEmpty().contains("货币") && !profile.name.contains("货币")) {
            return unavailable("该基金未识别为货币基金")
        }
        if (latestDay == null || latestIncome == null) return unavailable("缺少万份收益历史")
        if (ChronoUnit.DAYS.between(latestDay, today) !in 0..MAX_REPORT_AGE_DAYS) {
            return unavailable("最近万份收益超过 14 天，暂不外推")
        }
        if (points.size < HISTORY_WINDOW + MIN_VALIDATION_COUNT) {
            return unavailable("至少需要 ${HISTORY_WINDOW + MIN_VALIDATION_COUNT} 个公布日，当前 ${points.size} 个")
        }

        val validationStart = (points.size - VALIDATION_WINDOW).coerceAtLeast(HISTORY_WINDOW)
        val errors = (validationStart until points.size).mapNotNull { index ->
            val history = points.subList(index - HISTORY_WINDOW, index)
            val prediction = history.sumOf { it.second } / HISTORY_WINDOW
            val actual = points[index].second
            val persistencePrediction = points[index - 1].second
            Triple(prediction, actual, persistencePrediction)
                .takeIf { prediction.isFinite() && actual.isFinite() && persistencePrediction.isFinite() }
        }
        if (errors.size < MIN_VALIDATION_COUNT) return unavailable("万份收益样本外验证不足")

        val mae = errors.sumOf { (prediction, actual, _) -> abs(prediction - actual) } / errors.size
        val persistenceMae = errors.sumOf { (_, actual, persistence) -> abs(persistence - actual) } / errors.size
        if (!mae.isFinite() || !persistenceMae.isFinite() || persistenceMae <= 1e-9) {
            return unavailable("万份收益历史无法形成有效的最近一期基线")
        }
        val improvement = 1.0 - mae / persistenceMae
        if (improvement <= 0.0) return unavailable("近 7 个公布日均值未优于最近一期收益基线")

        val forecast = points.takeLast(HISTORY_WINDOW).sumOf { it.second } / HISTORY_WINDOW
        if (!forecast.isFinite()) return unavailable("万份收益参考值无效")
        val grade = when {
            improvement >= 0.75 -> FundEstimateGrade.HIGH
            improvement >= 0.40 -> FundEstimateGrade.MEDIUM
            else -> FundEstimateGrade.LOW
        }
        return FundMoneyYieldEstimate(
            code = profile.code,
            latestIncomePer10k = latestIncome,
            estimatedIncomePer10k = forecast,
            estimatedDailyReturnPct = forecast / 100.0,
            baseDate = latestDay.toString(),
            grade = grade,
            historyCount = points.size,
            validationCount = errors.size,
            validationMaePer10k = mae,
            persistenceBaselineMaePer10k = persistenceMae,
            source = profile.source,
            fetchedAt = profile.fetchedAt,
            reason = if (grade == FundEstimateGrade.LOW) "近 7 日均值仅略优于最近一期收益基线" else null
        )
    }
}
