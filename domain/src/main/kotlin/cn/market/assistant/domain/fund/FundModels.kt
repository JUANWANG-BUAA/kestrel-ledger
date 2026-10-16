package cn.market.assistant.domain.fund

enum class FundEstimateGrade { HIGH, MEDIUM, LOW, NONE, UNAVAILABLE }
enum class FundEstimateMethod {
    INDEX_OLS,
    LINKED_ETF_OLS,
    DISCLOSED_HOLDINGS_OLS,
    DISCLOSED_HOLDINGS,
    NONE,
    UNAVAILABLE
}

data class FundNavEstimate(
    val code: String,
    val grade: FundEstimateGrade,
    val method: FundEstimateMethod,
    val estimatedChangePct: Double?,
    val lowerBoundPct: Double?,
    val upperBoundPct: Double?,
    val estimatedUnitNav: Double?,
    val baseNavDate: String?,
    val trackingIndexId: String?,
    val trackingIndexName: String?,
    val source: String?,
    val sourceTime: Long?,
    val rSquared: Double?,
    val outOfSampleMaePct: Double?,
    val trainingCount: Int,
    val validationCount: Int,
    val inputCoveragePct: Double,
    val reason: String?,
    val inputHoldingsReportDate: String? = null,
    val calibrationRouteKey: String? = null,
    val calibrationSamples: List<FundNavResidualSample> = emptyList(),
    val intervalCalibration: FundNavIntervalCalibration? = null
)

/** One historical rolling prediction, retained locally for future error-interval calibration. */
data class FundNavResidualSample(
    val fundCode: String,
    val routeKey: String,
    val observationDay: String,
    val residualPct: Double,
    val modelAbsoluteErrorPct: Double,
    val zeroBaselineAbsoluteErrorPct: Double
)

data class FundNavIntervalCalibration(
    val targetCoveragePct: Double,
    val calibrationCount: Int,
    val auditCount: Int,
    val observedAuditCoveragePct: Double?,
    val radiusPct: Double?,
    val isUsable: Boolean
)

/** Exact exchange price compared with the fund's most recent published NAV. */
data class FundMarketPriceComparison(
    val marketSecid: String,
    val marketPrice: Double?,
    val latestNav: Double?,
    val navDate: String?,
    val premiumDiscountPct: Double?,
    val source: String?,
    val quoteTime: Long?,
    val reason: String?
)

/** Rolling reference for a money-market fund's daily income per 10,000 shares. */
data class FundMoneyYieldEstimate(
    val code: String,
    val latestIncomePer10k: Double?,
    val estimatedIncomePer10k: Double?,
    val estimatedDailyReturnPct: Double?,
    val baseDate: String?,
    val grade: FundEstimateGrade,
    val historyCount: Int,
    val validationCount: Int,
    val validationMaePer10k: Double?,
    val persistenceBaselineMaePer10k: Double?,
    val source: String,
    val fetchedAt: Long,
    val reason: String?
)

data class FundSearchResult(
    val code: String,
    val name: String,
    val category: String,
    val source: String,
    val fetchedAt: Long
)

data class FundNavPoint(
    val day: String,
    val unitNav: Double,
    val accumulatedNav: Double?,
    val changePct: Double?
)

data class FundNavHistoryCache(
    val code: String,
    val points: List<FundNavPoint>,
    val totalCount: Int,
    val firstDay: String?,
    val lastDay: String?,
    val cachedAt: Long?
)

data class FundNavHistorySyncResult(
    val cache: FundNavHistoryCache,
    val fetchedCount: Int,
    val source: String,
    val fetchedAt: Long
)

data class FundWatchlistEntry(
    val code: String,
    val name: String,
    val latest: FundNavPoint?,
    val navCount: Int,
    val cachedAt: Long?,
    val category: String? = null
)

data class FundHolding(
    val code: String,
    val name: String,
    val weightPct: Double,
    val marketSecid: String? = null
)

data class FundHoldingQuote(
    val code: String,
    val name: String,
    val changePct: Double,
    val quoteTime: Long,
    val source: String
)

data class FundProfile(
    val code: String,
    val name: String,
    val fundType: String?,
    val managementCompany: String?,
    val unitNav: Double?,
    val accumulatedNav: Double?,
    val changePct: Double?,
    val navDate: String?,
    val subscriptionStatus: String?,
    val redemptionStatus: String?,
    val riskLevel: String?,
    val navHistory: List<FundNavPoint>,
    val holdings: List<FundHolding>,
    val holdingsReportDate: String?,
    val source: String,
    val fetchedAt: Long,
    val navError: String? = null,
    val holdingsError: String? = null,
    val trackingIndexSecid: String? = null,
    val trackingIndexName: String? = null,
    val linkedEtfCode: String? = null,
    val linkedEtfName: String? = null
)
