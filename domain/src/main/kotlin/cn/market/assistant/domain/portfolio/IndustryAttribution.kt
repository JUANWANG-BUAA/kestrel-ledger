package cn.market.assistant.domain.portfolio

import java.math.BigDecimal
import java.math.RoundingMode

/** One current holding mapped to a classification bucket (industry or theme). */
data class IndustryHoldingInput(
    val assetId: String,
    val assetName: String,
    val marketValue: BigDecimal,
    val bucket: String?,
    val bucketName: String? = null,
    /** Holding return over the attribution period, when it can be measured. */
    val periodReturnPct: BigDecimal?
)

data class IndustryAttributionResult(
    val attribution: BrinsonAttributionResult?,
    val coveragePct: BigDecimal,
    val mappedAssetCount: Int,
    val unmappedAssetNames: List<String>,
    val missingReason: String?
)

/**
 * Maps current holdings into industry/theme buckets and delegates the single-period decomposition to
 * [BrinsonAttribution]. Unmapped holdings are never guessed: when coverage falls below the gate the
 * result carries a reason and no numbers.
 */
object IndustryAttribution {
    val DEFAULT_MINIMUM_COVERAGE_PCT = BigDecimal("80")

    fun attribute(
        holdings: List<IndustryHoldingInput>,
        benchmarkWeightsPct: Map<String, BigDecimal>,
        benchmarkReturnsPct: Map<String, BigDecimal>,
        minimumCoveragePct: BigDecimal = DEFAULT_MINIMUM_COVERAGE_PCT
    ): IndustryAttributionResult {
        if (holdings.isEmpty()) return empty("没有可用于行业归因的持仓")
        if (benchmarkWeightsPct.isEmpty() || benchmarkReturnsPct.isEmpty()) {
            return empty("缺少行业基准权重或基准收益，暂不展示行业归因")
        }
        val totalValue = holdings.sumOf { it.marketValue }
        if (totalValue.signum() <= 0) return empty("持仓市值为零，无法计算行业归因")

        val mapped = holdings.filter { !it.bucket.isNullOrBlank() }
        val unmapped = holdings.filter { it.bucket.isNullOrBlank() }
        val mappedValue = mapped.sumOf { it.marketValue }
        val coveragePct = mappedValue.multiply(BigDecimal("100"))
            .divide(totalValue, 2, RoundingMode.HALF_UP)
        if (coveragePct < minimumCoveragePct) {
            return IndustryAttributionResult(
                attribution = null,
                coveragePct = coveragePct,
                mappedAssetCount = mapped.size,
                unmappedAssetNames = unmapped.map { it.assetName },
                missingReason = "行业映射覆盖 ${coveragePct}%，低于 ${minimumCoveragePct.toPlainString()}% 门槛，" +
                    "未映射持仓：${unmapped.joinToString("、") { it.assetName }}"
            )
        }
        val missingBenchmark = mapped.firstOrNull { it.bucket !in benchmarkWeightsPct || it.bucket !in benchmarkReturnsPct }
        if (missingBenchmark != null) {
            return IndustryAttributionResult(
                attribution = null,
                coveragePct = coveragePct,
                mappedAssetCount = mapped.size,
                unmappedAssetNames = unmapped.map { it.assetName },
                missingReason = "行业「${missingBenchmark.bucketName ?: missingBenchmark.bucket}」缺少基准权重或收益"
            )
        }

        val portfolioValueByBucket = mapped.groupBy { it.bucket!! }.mapValues { (_, rows) ->
            rows.sumOf { it.marketValue }
        }
        val portfolioReturnByBucket = mapped.groupBy { it.bucket!! }.mapValues { (bucket, rows) ->
            val bucketValue = portfolioValueByBucket.getValue(bucket)
            rows.sumOf { row ->
                val weight = row.marketValue.divide(bucketValue, 18, RoundingMode.HALF_UP)
                weight.multiply(row.periodReturnPct ?: BigDecimal.ZERO)
            }
        }
        val hasUnmeasuredReturn = mapped.any { it.periodReturnPct == null }
        val sectors = mutableListOf<BrinsonSectorInput>()
        val benchmarkWeightSum = benchmarkWeightsPct.values.sumOf { it }
        val cashWeight = BigDecimal("100").subtract(coveragePct.coerceAtMost(BigDecimal("100")))
        portfolioValueByBucket.forEach { (bucket, value) ->
            val portfolioWeight = value.multiply(BigDecimal("100")).divide(totalValue, 4, RoundingMode.HALF_UP)
            val benchmarkWeight = benchmarkWeightsPct.getValue(bucket)
            sectors += BrinsonSectorInput(
                key = bucket,
                name = bucket,
                portfolioWeightPct = portfolioWeight,
                benchmarkWeightPct = benchmarkWeight,
                portfolioReturnPct = portfolioReturnByBucket.getValue(bucket),
                benchmarkReturnPct = benchmarkReturnsPct.getValue(bucket)
            )
        }
        if (cashWeight > BigDecimal("0.01")) {
            sectors += BrinsonSectorInput(
                key = "cash",
                name = "现金/未映射",
                portfolioWeightPct = cashWeight,
                benchmarkWeightPct = BigDecimal.ZERO,
                portfolioReturnPct = BigDecimal.ZERO,
                benchmarkReturnPct = BigDecimal.ZERO,
                cashLike = true
            )
        }
        // Brinson requires both weight vectors to sum to 100; the cash rows absorb the residual.
        val benchmarkCashWeight = BigDecimal("100").subtract(benchmarkWeightSum)
        if (benchmarkCashWeight > BigDecimal("0.01")) {
            sectors += BrinsonSectorInput(
                key = "benchmark-cash",
                name = "基准现金",
                portfolioWeightPct = BigDecimal.ZERO,
                benchmarkWeightPct = benchmarkCashWeight,
                portfolioReturnPct = BigDecimal.ZERO,
                benchmarkReturnPct = BigDecimal.ZERO,
                cashLike = true
            )
        }
        val attribution = BrinsonAttribution.singlePeriod(sectors)
        val missingReason = when {
            hasUnmeasuredReturn ->
                "部分持仓缺少区间收益，行业收益仅按已测量持仓计算；未测量部分未假设为零以外的值"
            else -> attribution.missingReason
        }
        return IndustryAttributionResult(
            attribution = attribution,
            coveragePct = coveragePct,
            mappedAssetCount = mapped.size,
            unmappedAssetNames = unmapped.map { it.assetName },
            missingReason = missingReason
        )
    }

    private fun empty(reason: String) = IndustryAttributionResult(
        attribution = null,
        coveragePct = BigDecimal.ZERO.setScale(2),
        mappedAssetCount = 0,
        unmappedAssetNames = emptyList(),
        missingReason = reason
    )
}
