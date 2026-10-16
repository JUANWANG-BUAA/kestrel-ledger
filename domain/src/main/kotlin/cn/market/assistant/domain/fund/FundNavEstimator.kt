package cn.market.assistant.domain.fund

import cn.market.assistant.domain.market.MarketOhlcBar
import cn.market.assistant.domain.market.MarketQuote
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil

/** Conservative Android route for index-linked funds; unsupported routes never emit a numeric estimate. */
object FundNavEstimator {
    private const val TRAINING_WINDOW = 60
    private const val VALIDATION_WINDOW = 20
    private const val MIN_VALIDATION_COUNT = 15
    private const val MAX_QUOTE_AGE_SECONDS = 36 * 60 * 60L
    private const val MAX_HOLDINGS_REPORT_AGE_DAYS = 180L
    private const val MIN_HOLDING_QUOTE_COUNT = 3
    private const val MIN_HOLDING_COVERAGE_PCT = 15.0
    private const val T1_TRAINING_WINDOW = 60
    private const val T1_VALIDATION_WINDOW = 20
    private const val T1_MIN_VALIDATION_COUNT = 15
    private const val T1_MIN_HOLDING_COUNT = 5
    private const val T1_MIN_COVERAGE_PCT = 30.0
    private const val T1_MIN_DAILY_COVERAGE_SHARE = 0.80
    private const val MAX_BASE_NAV_AGE_DAYS = 14L
    private const val INTERVAL_TARGET_COVERAGE = 0.90
    private const val MIN_INTERVAL_CALIBRATION_COUNT = 40
    private const val INTERVAL_AUDIT_COUNT = 20
    private const val MIN_INTERVAL_AUDIT_COVERAGE_PCT = 80.0
    private const val MAX_INTERVAL_CALIBRATION_WINDOW = 250
    private const val MAX_OOS_RESIDUAL_SAMPLES_PER_REFRESH = 60
    private val OLS_METHODS = setOf(
        FundEstimateMethod.INDEX_OLS,
        FundEstimateMethod.LINKED_ETF_OLS,
        FundEstimateMethod.DISCLOSED_HOLDINGS_OLS
    )
    private val marketZone = ZoneId.of("Asia/Shanghai")

    fun usesIndexBenchmarkRoute(profile: FundProfile): Boolean {
        val fundType = profile.fundType.orEmpty()
        return !fundType.contains("QDII", ignoreCase = true) &&
            (fundType.contains("指数型") || profile.name.contains("ETF联接"))
    }

    fun usesDisclosedHoldingsRoute(profile: FundProfile): Boolean {
        val fundType = profile.fundType.orEmpty()
        return !fundType.contains("QDII", ignoreCase = true) && !usesIndexBenchmarkRoute(profile) &&
            (fundType.contains("股票") || fundType.contains("混合"))
    }

    /** Fits a 90% absolute-residual interval and checks it on the latest untouched OOS observations. */
    fun calibrateInterval(
        estimate: FundNavEstimate,
        historicalSamples: List<FundNavResidualSample>
    ): FundNavEstimate {
        val routeKey = estimate.calibrationRouteKey
        if (estimate.method !in OLS_METHODS || estimate.estimatedChangePct == null || routeKey == null) {
            return estimate.copy(lowerBoundPct = null, upperBoundPct = null, intervalCalibration = null)
        }

        val observations = (historicalSamples + estimate.calibrationSamples)
            .asSequence()
            .filter { sample ->
                sample.fundCode == estimate.code && sample.routeKey == routeKey &&
                    sample.residualPct.isFinite()
            }
            .distinctBy { it.observationDay }
            .sortedBy { it.observationDay }
            .toList()
        val minimumCount = MIN_INTERVAL_CALIBRATION_COUNT + INTERVAL_AUDIT_COUNT
        if (observations.size < minimumCount) {
            return estimate.copy(
                lowerBoundPct = null,
                upperBoundPct = null,
                intervalCalibration = FundNavIntervalCalibration(
                    targetCoveragePct = INTERVAL_TARGET_COVERAGE * 100.0,
                    calibrationCount = observations.size,
                    auditCount = 0,
                    observedAuditCoveragePct = null,
                    radiusPct = null,
                    isUsable = false
                )
            )
        }

        val audit = observations.takeLast(INTERVAL_AUDIT_COUNT)
        val calibration = observations.dropLast(INTERVAL_AUDIT_COUNT).takeLast(MAX_INTERVAL_CALIBRATION_WINDOW)
        val sortedAbsoluteResiduals = calibration.map { abs(it.residualPct) }.sorted()
        val quantileRank = ceil((sortedAbsoluteResiduals.size + 1) * INTERVAL_TARGET_COVERAGE)
            .toInt().coerceIn(1, sortedAbsoluteResiduals.size)
        val radiusPct = sortedAbsoluteResiduals[quantileRank - 1]
        val auditCoveragePct = audit.count { abs(it.residualPct) <= radiusPct } * 100.0 / audit.size
        val usable = radiusPct.isFinite() && auditCoveragePct >= MIN_INTERVAL_AUDIT_COVERAGE_PCT
        val intervalCalibration = FundNavIntervalCalibration(
            targetCoveragePct = INTERVAL_TARGET_COVERAGE * 100.0,
            calibrationCount = calibration.size,
            auditCount = audit.size,
            observedAuditCoveragePct = auditCoveragePct,
            radiusPct = radiusPct,
            isUsable = usable
        )
        val reason = if (usable) estimate.reason else {
            val coverage = String.format(java.util.Locale.US, "%.0f", auditCoveragePct)
            listOfNotNull(
                estimate.reason,
                "近 ${audit.size} 个独立样本的误差区间覆盖为 ${coverage}%，低于 80% 校验门槛，已隐藏区间"
            ).joinToString("；")
        }
        return estimate.copy(
            lowerBoundPct = if (usable) estimate.estimatedChangePct - radiusPct else null,
            upperBoundPct = if (usable) estimate.estimatedChangePct + radiusPct else null,
            intervalCalibration = intervalCalibration,
            reason = reason
        )
    }

    fun estimate(
        profile: FundProfile,
        indexBars: List<MarketOhlcBar>,
        indexQuote: MarketQuote?,
        holdingQuotes: List<FundHoldingQuote> = emptyList(),
        now: Long = System.currentTimeMillis() / 1000,
        holdingBars: Map<String, List<MarketOhlcBar>> = emptyMap()
    ): FundNavEstimate {
        val classification = "${profile.name} ${profile.fundType.orEmpty()}"
        if (classification.contains("短债") || classification.contains("中短债") || classification.contains("同业存单")) {
            return emptyResult(
                profile,
                FundEstimateGrade.NONE,
                "该品类的盘中噪声高于有效信号，不提供估算数字"
            )
        }
        if (classification.contains("货币")) {
            return emptyResult(
                profile,
                FundEstimateGrade.UNAVAILABLE,
                "货币基金按万份收益单独展示，不生成盘中单位净值估算"
            )
        }

        val fundType = profile.fundType.orEmpty()
        if (fundType.contains("QDII", ignoreCase = true)) {
            return emptyResult(
                profile,
                FundEstimateGrade.UNAVAILABLE,
                "QDII 净值时区与披露滞后尚未校准，暂不平移指数收益"
            )
        }
        val isIndexLinked = usesIndexBenchmarkRoute(profile)
        if (!isIndexLinked) {
            if (usesDisclosedHoldingsRoute(profile)) {
                return estimateDisclosedHoldings(profile, holdingQuotes, holdingBars, now)
            }
            return emptyResult(
                profile,
                FundEstimateGrade.UNAVAILABLE,
                "Android 当前尚无该品类的已接入估算路由"
            )
        }

        val indexId = profile.trackingIndexSecid
        val linkedEtfId = if (indexId == null) {
            FundListedPriceAnalytics.marketSecid(profile.linkedEtfCode.orEmpty())
        } else null
        val benchmarkId = indexId ?: linkedEtfId
        if (benchmarkId == null) {
            return emptyResult(
                profile,
                FundEstimateGrade.UNAVAILABLE,
                "没有可验证的跟踪指数，暂不对该基金生成估算"
            )
        }
        if (indexQuote == null || indexQuote.secid != benchmarkId) {
            return emptyResult(profile, FundEstimateGrade.UNAVAILABLE, "缺少对应跟踪指数或联接 ETF 的当前行情")
        }
        if (!indexQuote.changePct.isFinite() || indexQuote.quoteTime <= 0 || indexQuote.fetchedAt <= 0 ||
            indexQuote.fetchedAt > now + 300 || now - indexQuote.fetchedAt > MAX_QUOTE_AGE_SECONDS) {
            return emptyResult(profile, FundEstimateGrade.UNAVAILABLE, "跟踪指数或联接 ETF 行情已过期或字段无效", indexQuote)
        }

        val quoteDay = runCatching {
            Instant.ofEpochSecond(indexQuote.quoteTime).atZone(marketZone).toLocalDate().toString()
        }.getOrNull() ?: return emptyResult(profile, FundEstimateGrade.UNAVAILABLE, "无法识别行情日期", indexQuote)
        val navByDay = profile.navHistory.filter { it.day < quoteDay }.associateBy { it.day }
        val baseNav = navByDay.values.maxByOrNull { it.day }
            ?: return emptyResult(profile, FundEstimateGrade.UNAVAILABLE, "基准行情当日没有可用的前一日基金净值", indexQuote)

        val fundReturns = fundReturns(profile.navHistory)
        val indexReturns = indexReturns(indexBars)
        val paired = fundReturns.keys.intersect(indexReturns.keys).sorted()
            .filter { it < quoteDay }
            .mapNotNull { day ->
                val fundReturn = fundReturns[day] ?: return@mapNotNull null
                val indexReturn = indexReturns[day] ?: return@mapNotNull null
                if (fundReturn.isFinite() && indexReturn.isFinite()) ReturnPair(day, indexReturn, fundReturn) else null
            }
        if (paired.size < TRAINING_WINDOW + MIN_VALIDATION_COUNT) {
            return emptyResult(
                profile,
                FundEstimateGrade.UNAVAILABLE,
                "对齐净值与指数历史不足 ${TRAINING_WINDOW + MIN_VALIDATION_COUNT} 个交易日",
                indexQuote,
                coverage = coveragePct(paired.size, profile.navHistory.size)
            )
        }

        val validationStart = (paired.size - VALIDATION_WINDOW).coerceAtLeast(TRAINING_WINDOW)
        val residualStart = (paired.size - MAX_OOS_RESIDUAL_SAMPLES_PER_REFRESH).coerceAtLeast(TRAINING_WINDOW)
        val walkForwardPredictions = mutableListOf<OosPrediction>()
        val validationPredictions = mutableListOf<OosPrediction>()
        for (index in residualStart until paired.size) {
            val model = fit(paired.subList(index - TRAINING_WINDOW, index)) ?: continue
            val point = paired[index]
            val prediction = OosPrediction(point.day, model.predict(point.indexPct), point.fundPct)
            walkForwardPredictions += prediction
            if (index >= validationStart) validationPredictions += prediction
        }
        if (validationPredictions.size < MIN_VALIDATION_COUNT) {
            return emptyResult(
                profile,
                FundEstimateGrade.UNAVAILABLE,
                "样本外验证数据不足，暂不显示估算数字",
                indexQuote,
                coverage = coveragePct(paired.size, profile.navHistory.size)
            )
        }

        val mae = validationPredictions.sumOf { abs(it.actualPct - it.predictedPct) } / validationPredictions.size
        val mse = validationPredictions.sumOf {
            (it.actualPct - it.predictedPct) * (it.actualPct - it.predictedPct)
        } / validationPredictions.size
        val zeroBaselineMae = validationPredictions.sumOf { abs(it.actualPct) } / validationPredictions.size
        val baselineMse = validationPredictions.sumOf { it.actualPct * it.actualPct } / validationPredictions.size
        val rSquared = if (baselineMse > 1e-12) 1.0 - mse / baselineMse else null
        val method = if (linkedEtfId != null) FundEstimateMethod.LINKED_ETF_OLS else FundEstimateMethod.INDEX_OLS
        val routeKey = if (linkedEtfId != null) "v1:linked-etf:$linkedEtfId" else "v1:index:$benchmarkId"
        val calibrationSamples = residualSamples(profile.code, routeKey, walkForwardPredictions)
        if (!mae.isFinite() || !mse.isFinite() || !zeroBaselineMae.isFinite() ||
            rSquared == null || !rSquared.isFinite() || rSquared <= 0.0 || mae >= zeroBaselineMae
        ) {
            return emptyResult(
                profile,
                FundEstimateGrade.UNAVAILABLE,
                "滚动样本外 MAE 未优于零收益基线，已暂停该估值路由",
                indexQuote,
                coverage = coveragePct(paired.size, profile.navHistory.size)
            ).copy(calibrationRouteKey = routeKey, calibrationSamples = calibrationSamples)
        }
        val model = fit(paired.takeLast(TRAINING_WINDOW))
            ?: return emptyResult(profile, FundEstimateGrade.UNAVAILABLE, "基准回归拟合失败", indexQuote)
        val estimatePct = model.predict(indexQuote.changePct)
        if (!estimatePct.isFinite()) {
            return emptyResult(profile, FundEstimateGrade.UNAVAILABLE, "估算结果无效", indexQuote)
        }
        val grade = when {
            rSquared >= 0.95 && mae <= 0.1 -> FundEstimateGrade.HIGH
            rSquared >= 0.85 || mae <= 0.6 -> FundEstimateGrade.MEDIUM
            else -> FundEstimateGrade.LOW
        }
        return FundNavEstimate(
            code = profile.code,
            grade = grade,
            method = method,
            estimatedChangePct = estimatePct,
            lowerBoundPct = null,
            upperBoundPct = null,
            estimatedUnitNav = baseNav.unitNav * (1.0 + estimatePct / 100.0),
            baseNavDate = baseNav.day,
            trackingIndexId = benchmarkId,
            trackingIndexName = if (linkedEtfId != null) profile.linkedEtfName ?: "联接目标 ETF" else profile.trackingIndexName,
            source = indexQuote.source,
            sourceTime = indexQuote.quoteTime,
            rSquared = rSquared,
            outOfSampleMaePct = mae,
            trainingCount = TRAINING_WINDOW,
            validationCount = validationPredictions.size,
            inputCoveragePct = coveragePct(paired.size, profile.navHistory.size),
            reason = if (grade == FundEstimateGrade.LOW) "近 ${validationPredictions.size} 个样本外交易日表现偏弱，请谨慎参考" else null,
            calibrationRouteKey = routeKey,
            calibrationSamples = calibrationSamples
        )
    }

    private fun estimateDisclosedHoldings(
        profile: FundProfile,
        quotes: List<FundHoldingQuote>,
        holdingBars: Map<String, List<MarketOhlcBar>>,
        now: Long
    ): FundNavEstimate {
        val attempt = estimateDisclosedHoldingsRegression(profile, quotes, holdingBars, now)
        if (attempt.estimate != null) return attempt.estimate
        val snapshot = estimateDisclosedHoldingsSnapshot(profile, quotes, now)
        if (attempt.reason == null) return snapshot
        val fallbackReason = "T1 历史回归未通过覆盖或样本外门槛：${attempt.reason}；已降级为披露重仓即时参考"
        return snapshot.copy(
            reason = listOfNotNull(snapshot.reason, fallbackReason).joinToString("；"),
            calibrationRouteKey = attempt.calibrationRouteKey,
            calibrationSamples = attempt.calibrationSamples
        )
    }

    private fun estimateDisclosedHoldingsRegression(
        profile: FundProfile,
        quotes: List<FundHoldingQuote>,
        holdingBars: Map<String, List<MarketOhlcBar>>,
        now: Long
    ): HoldingsRegressionAttempt {
        val reportDate = profile.holdingsReportDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return HoldingsRegressionAttempt(null, "缺少可识别的重仓披露日期")
        val today = Instant.ofEpochSecond(now).atZone(marketZone).toLocalDate()
        val reportAge = ChronoUnit.DAYS.between(reportDate, today)
        if (reportAge !in 0..MAX_HOLDINGS_REPORT_AGE_DAYS) {
            return HoldingsRegressionAttempt(null, "重仓报告截止日期超过 180 天")
        }
        val quoteDay = today.toString()
        val baseNav = profile.navHistory.filter { it.day < quoteDay }.maxByOrNull { it.day }
            ?: return HoldingsRegressionAttempt(null, "缺少当前交易日前一日基金净值")
        if (runCatching { ChronoUnit.DAYS.between(LocalDate.parse(baseNav.day), today) }.getOrDefault(Long.MAX_VALUE) > MAX_BASE_NAV_AGE_DAYS) {
            return HoldingsRegressionAttempt(null, "最近公布净值超过 14 天")
        }

        val quotesByCode = quotes.associateBy { it.code }
        val supported = profile.holdings.distinctBy { it.code }.mapNotNull { holding ->
            if (!holding.weightPct.isFinite() || holding.weightPct <= 0.0) return@mapNotNull null
            val secid = holding.marketSecid ?: return@mapNotNull null
            val bars = holdingBars[holding.code].orEmpty()
            val quote = quotesByCode[holding.code] ?: return@mapNotNull null
            val age = now - quote.quoteTime
            val quoteDate = runCatching {
                Instant.ofEpochSecond(quote.quoteTime).atZone(marketZone).toLocalDate().toString()
            }.getOrNull()
            if (!quote.changePct.isFinite() || quote.quoteTime <= 0 || age !in 0..MAX_QUOTE_AGE_SECONDS ||
                quoteDate != quoteDay || bars.size < T1_TRAINING_WINDOW + T1_MIN_VALIDATION_COUNT + 1
            ) return@mapNotNull null
            val returns = stockReturns(bars)
            if (returns.size < T1_TRAINING_WINDOW + T1_MIN_VALIDATION_COUNT) return@mapNotNull null
            SupportedHolding(holding, quote, returns)
        }
        val coverage = supported.sumOf { it.holding.weightPct }.coerceIn(0.0, 100.0)
        if (supported.size < T1_MIN_HOLDING_COUNT || coverage < T1_MIN_COVERAGE_PCT) {
            return HoldingsRegressionAttempt(
                null,
                "可复权历史的重仓覆盖 ${String.format(java.util.Locale.US, "%.1f", coverage)}%，至少需要 " +
                    "$T1_MIN_HOLDING_COUNT 只标的和 ${T1_MIN_COVERAGE_PCT.toInt()}% 权重"
            )
        }

        val supportedCoverage = supported.sumOf { it.holding.weightPct }
        val candidateDays = supported.flatMap { it.dailyReturns.keys }.distinct().sorted()
        val basketReturns = buildMap {
            candidateDays.forEach { day ->
                val contributions = supported.mapNotNull { holding ->
                    holding.dailyReturns[day]?.let { holding.holding.weightPct to it }
                }
                val dailyCoverage = contributions.sumOf { it.first }
                if (dailyCoverage >= T1_MIN_COVERAGE_PCT &&
                    dailyCoverage >= supportedCoverage * T1_MIN_DAILY_COVERAGE_SHARE
                ) {
                    put(day, contributions.sumOf { (weight, changePct) -> weight * changePct } / 100.0)
                }
            }
        }
        val fundReturns = fundReturns(profile.navHistory)
        val paired = basketReturns.keys.intersect(fundReturns.keys).sorted()
            .filter { it < quoteDay }
            .mapNotNull { day ->
                val basketPct = basketReturns[day] ?: return@mapNotNull null
                val fundPct = fundReturns[day] ?: return@mapNotNull null
                if (basketPct.isFinite() && fundPct.isFinite()) ReturnPair(day, basketPct, fundPct) else null
            }
        val minimumHistory = T1_TRAINING_WINDOW + T1_MIN_VALIDATION_COUNT
        if (paired.size < minimumHistory) {
            return HoldingsRegressionAttempt(null, "对齐的基金净值与披露股池日线仅 ${paired.size} 个交易日，需要至少 $minimumHistory 日")
        }

        val validationStart = (paired.size - T1_VALIDATION_WINDOW).coerceAtLeast(T1_TRAINING_WINDOW)
        val residualStart = (paired.size - MAX_OOS_RESIDUAL_SAMPLES_PER_REFRESH).coerceAtLeast(T1_TRAINING_WINDOW)
        val walkForwardPredictions = mutableListOf<OosPrediction>()
        val validationPredictions = mutableListOf<OosPrediction>()
        for (index in residualStart until paired.size) {
            val model = fit(paired.subList(index - T1_TRAINING_WINDOW, index)) ?: continue
            val point = paired[index]
            val prediction = OosPrediction(point.day, model.predict(point.indexPct), point.fundPct)
            walkForwardPredictions += prediction
            if (index >= validationStart) validationPredictions += prediction
        }
        if (validationPredictions.size < T1_MIN_VALIDATION_COUNT) {
            return HoldingsRegressionAttempt(null, "只有 ${validationPredictions.size} 个可用样本外预测，需要至少 $T1_MIN_VALIDATION_COUNT 日")
        }

        val mae = validationPredictions.sumOf { abs(it.actualPct - it.predictedPct) } / validationPredictions.size
        val mse = validationPredictions.sumOf {
            (it.actualPct - it.predictedPct) * (it.actualPct - it.predictedPct)
        } / validationPredictions.size
        val zeroBaselineMae = validationPredictions.sumOf { abs(it.actualPct) } / validationPredictions.size
        val zeroBaselineMse = validationPredictions.sumOf { it.actualPct * it.actualPct } / validationPredictions.size
        val rSquared = if (zeroBaselineMse > 1e-12) 1.0 - mse / zeroBaselineMse else null
        val routeKey = holdingsRouteKey(reportDate, supported)
        val calibrationSamples = residualSamples(profile.code, routeKey, walkForwardPredictions)
        if (!mae.isFinite() || !mse.isFinite() || !zeroBaselineMae.isFinite() || rSquared == null ||
            rSquared <= 0.0 || mae >= zeroBaselineMae
        ) {
            return HoldingsRegressionAttempt(
                null,
                "样本外 MAE ${formatPct(mae)} 未优于零收益基线 ${formatPct(zeroBaselineMae)}",
                routeKey,
                calibrationSamples
            )
        }

        val currentBasketPct = supported.sumOf { it.holding.weightPct * it.quote.changePct } / 100.0
        val model = fit(paired.takeLast(T1_TRAINING_WINDOW))
            ?: return HoldingsRegressionAttempt(null, "最近 60 日回归无法稳定拟合")
        val estimatePct = model.predict(currentBasketPct)
        if (!estimatePct.isFinite()) return HoldingsRegressionAttempt(null, "当前股池回归结果无效")
        val grade = when {
            rSquared >= 0.90 && mae <= 0.30 -> FundEstimateGrade.HIGH
            rSquared >= 0.65 && mae <= 0.60 -> FundEstimateGrade.MEDIUM
            else -> FundEstimateGrade.LOW
        }
        val sourceTime = supported.minOfOrNull { it.quote.quoteTime }
        val source = (supported.map { it.quote.source } + "tencent-qfq").distinct().joinToString("+")
        return HoldingsRegressionAttempt(
            estimate = FundNavEstimate(
                code = profile.code,
                grade = grade,
                method = FundEstimateMethod.DISCLOSED_HOLDINGS_OLS,
                estimatedChangePct = estimatePct,
                lowerBoundPct = null,
                upperBoundPct = null,
                estimatedUnitNav = baseNav.unitNav * (1.0 + estimatePct / 100.0),
                baseNavDate = baseNav.day,
                trackingIndexId = null,
                trackingIndexName = "披露重仓股池",
                source = source,
                sourceTime = sourceTime,
                rSquared = rSquared,
                outOfSampleMaePct = mae,
                trainingCount = T1_TRAINING_WINDOW,
                validationCount = validationPredictions.size,
                inputCoveragePct = coverage,
                reason = if (grade == FundEstimateGrade.LOW) "T1 模型虽胜过零收益基线，但样本外误差仍偏高" else null,
                inputHoldingsReportDate = reportDate.toString(),
                calibrationRouteKey = routeKey,
                calibrationSamples = calibrationSamples
            ),
            reason = null,
            calibrationRouteKey = routeKey,
            calibrationSamples = calibrationSamples
        )
    }

    private fun estimateDisclosedHoldingsSnapshot(
        profile: FundProfile,
        quotes: List<FundHoldingQuote>,
        now: Long
    ): FundNavEstimate {
        val reportDate = profile.holdingsReportDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return emptyResult(profile, FundEstimateGrade.UNAVAILABLE, "缺少可识别的重仓披露日期")
        val asOf = Instant.ofEpochSecond(now).atZone(marketZone).toLocalDate()
        val reportAge = ChronoUnit.DAYS.between(reportDate, asOf)
        if (reportAge !in 0..MAX_HOLDINGS_REPORT_AGE_DAYS) {
            return emptyResult(profile, FundEstimateGrade.UNAVAILABLE, "重仓披露已超过 180 天，暂不使用过期持仓估算")
        }
        val quoteDay = asOf.toString()
        val baseNav = profile.navHistory.filter { it.day < quoteDay }.maxByOrNull { it.day }
            ?: return emptyResult(profile, FundEstimateGrade.UNAVAILABLE, "缺少当前交易日前一日基金净值")
        val quotesByCode = quotes.associateBy { it.code }
        val included = profile.holdings.distinctBy { it.code }.mapNotNull { holding ->
            if (!holding.weightPct.isFinite() || holding.weightPct <= 0.0) return@mapNotNull null
            val quote = quotesByCode[holding.code] ?: return@mapNotNull null
            val age = now - quote.quoteTime
            val quoteDate = runCatching {
                Instant.ofEpochSecond(quote.quoteTime).atZone(marketZone).toLocalDate().toString()
            }.getOrNull()
            if (!quote.changePct.isFinite() || quote.quoteTime <= 0 || age !in 0..MAX_QUOTE_AGE_SECONDS || quoteDate != quoteDay) {
                return@mapNotNull null
            }
            holding to quote
        }
        val coverage = included.sumOf { (holding, _) -> holding.weightPct }.coerceIn(0.0, 100.0)
        if (included.size < MIN_HOLDING_QUOTE_COUNT || coverage < MIN_HOLDING_COVERAGE_PCT) {
            return emptyResult(
                profile,
                FundEstimateGrade.UNAVAILABLE,
                "可用重仓行情覆盖 ${String.format(java.util.Locale.US, "%.1f", coverage)}%，不足以生成估算",
                coverage = coverage
            )
        }
        val estimatePct = included.sumOf { (holding, quote) -> holding.weightPct * quote.changePct } / 100.0
        if (!estimatePct.isFinite()) {
            return emptyResult(profile, FundEstimateGrade.UNAVAILABLE, "披露持仓估算结果无效", coverage = coverage)
        }
        val sourceTime = included.minOfOrNull { it.second.quoteTime }
        val source = included.map { it.second.source }.distinct().joinToString("+")
        return FundNavEstimate(
            code = profile.code,
            grade = FundEstimateGrade.LOW,
            method = FundEstimateMethod.DISCLOSED_HOLDINGS,
            estimatedChangePct = estimatePct,
            lowerBoundPct = null,
            upperBoundPct = null,
            estimatedUnitNav = baseNav.unitNav * (1.0 + estimatePct / 100.0),
            baseNavDate = baseNav.day,
            trackingIndexId = null,
            trackingIndexName = null,
            source = source,
            sourceTime = sourceTime,
            rSquared = null,
            outOfSampleMaePct = null,
            trainingCount = 0,
            validationCount = 0,
            inputCoveragePct = coverage,
            reason = "仅按 ${reportDate} 重仓报告计算已披露部分，未披露部分不作外推；误差尚未完成 Android 样本外校准",
            inputHoldingsReportDate = reportDate.toString()
        )
    }

    private fun emptyResult(
        profile: FundProfile,
        grade: FundEstimateGrade,
        reason: String,
        quote: MarketQuote? = null,
        coverage: Double = 0.0
    ) = FundNavEstimate(
        code = profile.code,
        grade = grade,
        method = if (grade == FundEstimateGrade.NONE) FundEstimateMethod.NONE else FundEstimateMethod.UNAVAILABLE,
        estimatedChangePct = null,
        lowerBoundPct = null,
        upperBoundPct = null,
        estimatedUnitNav = null,
        baseNavDate = null,
        trackingIndexId = profile.trackingIndexSecid ?: FundListedPriceAnalytics.marketSecid(profile.linkedEtfCode.orEmpty()),
        trackingIndexName = profile.trackingIndexName ?: profile.linkedEtfName,
        source = quote?.source,
        sourceTime = quote?.quoteTime?.takeIf { it > 0 },
        rSquared = null,
        outOfSampleMaePct = null,
        trainingCount = 0,
        validationCount = 0,
        inputCoveragePct = coverage,
        reason = reason
    )

    private fun fundReturns(points: List<FundNavPoint>): Map<String, Double> {
        val ordered = points.sortedBy { it.day }
        return ordered.mapIndexedNotNull { index, point ->
            val change = point.changePct?.takeIf(Double::isFinite) ?: ordered.getOrNull(index - 1)?.let { previous ->
                if (previous.unitNav > 0) (point.unitNav / previous.unitNav - 1.0) * 100.0 else null
            }
            change?.let { point.day to it }
        }.toMap()
    }

    private fun indexReturns(bars: List<MarketOhlcBar>): Map<String, Double> {
        val ordered = bars.filter { it.close.isFinite() && it.close > 0 }.distinctBy { it.day }.sortedBy { it.day }
        return ordered.mapIndexedNotNull { index, bar ->
            val previous = ordered.getOrNull(index - 1) ?: return@mapIndexedNotNull null
            (bar.close / previous.close - 1.0).takeIf(Double::isFinite)?.let { bar.day to (it * 100.0) }
        }.toMap()
    }

    private fun stockReturns(bars: List<MarketOhlcBar>): Map<String, Double> {
        val ordered = bars.filter { it.close.isFinite() && it.close > 0.0 }
            .distinctBy { it.day }
            .sortedBy { it.day }
        return ordered.mapIndexedNotNull { index, bar ->
            val previous = ordered.getOrNull(index - 1) ?: return@mapIndexedNotNull null
            val changePct = (bar.close / previous.close - 1.0) * 100.0
            changePct.takeIf(Double::isFinite)?.let { bar.day to it }
        }.toMap()
    }

    private fun formatPct(value: Double): String =
        if (value.isFinite()) "${String.format(java.util.Locale.US, "%.3f", value)}%" else "—"

    private fun residualSamples(
        fundCode: String,
        routeKey: String,
        predictions: List<OosPrediction>
    ): List<FundNavResidualSample> = predictions.mapNotNull { prediction ->
        val residual = prediction.actualPct - prediction.predictedPct
        val modelError = abs(residual)
        val baselineError = abs(prediction.actualPct)
        if (!residual.isFinite() || !modelError.isFinite() || !baselineError.isFinite()) return@mapNotNull null
        FundNavResidualSample(
            fundCode = fundCode,
            routeKey = routeKey,
            observationDay = prediction.day,
            residualPct = residual,
            modelAbsoluteErrorPct = modelError,
            zeroBaselineAbsoluteErrorPct = baselineError
        )
    }

    private fun holdingsRouteKey(reportDate: LocalDate, holdings: List<SupportedHolding>): String {
        val signature = holdings.sortedBy { it.holding.code }.joinToString("|") { holding ->
            "${holding.holding.code}:${holding.holding.weightPct.toBits()}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray(Charsets.UTF_8))
        val fingerprint = digest.take(8).joinToString("") { byte ->
            "%02x".format(java.util.Locale.US, byte.toInt() and 0xff)
        }
        return "v1:holdings:$reportDate:$fingerprint"
    }

    private fun fit(pairs: List<ReturnPair>): LinearModel? {
        if (pairs.size < 2) return null
        val meanIndex = pairs.sumOf { it.indexPct } / pairs.size
        val meanFund = pairs.sumOf { it.fundPct } / pairs.size
        val variance = pairs.sumOf { (it.indexPct - meanIndex) * (it.indexPct - meanIndex) }
        if (!variance.isFinite() || variance <= 1e-12) return null
        val covariance = pairs.sumOf { (it.indexPct - meanIndex) * (it.fundPct - meanFund) }
        val beta = covariance / variance
        val alpha = meanFund - beta * meanIndex
        return if (alpha.isFinite() && beta.isFinite()) LinearModel(alpha, beta) else null
    }

    private fun coveragePct(covered: Int, available: Int): Double =
        if (available <= 0) 0.0 else (covered * 100.0 / available).coerceIn(0.0, 100.0)

    private data class ReturnPair(val day: String, val indexPct: Double, val fundPct: Double)
    private data class OosPrediction(val day: String, val predictedPct: Double, val actualPct: Double)
    private data class SupportedHolding(
        val holding: FundHolding,
        val quote: FundHoldingQuote,
        val dailyReturns: Map<String, Double>
    )
    private data class HoldingsRegressionAttempt(
        val estimate: FundNavEstimate?,
        val reason: String?,
        val calibrationRouteKey: String? = null,
        val calibrationSamples: List<FundNavResidualSample> = emptyList()
    )
    private data class LinearModel(val alpha: Double, val beta: Double) {
        fun predict(indexPct: Double): Double = alpha + beta * indexPct
    }
}
