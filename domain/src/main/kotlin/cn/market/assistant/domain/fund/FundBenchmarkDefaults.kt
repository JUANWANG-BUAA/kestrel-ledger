package cn.market.assistant.domain.fund

/**
 * Default public benchmarks for fund categories whose official profile does not disclose a tracking
 * index. All identifiers are EastMoney secids; the Android market layer resolves the same code to the
 * Tencent feed as a fallback, and every default is a broad, published index instead of a fund.
 */
object FundBenchmarkDefaults {
    /** 上证国债指数，用于中长期纯债与债券指数基金。 */
    const val TREASURY_INDEX_SECID = "1.000012"
    const val TREASURY_INDEX_NAME = "上证国债指数"

    /** 上证企债指数，用于信用债与产业债方向。 */
    const val CORPORATE_BOND_INDEX_SECID = "1.000013"
    const val CORPORATE_BOND_INDEX_NAME = "上证企债指数"

    /** 中证转债指数，用于可转债基金与转债方向。 */
    const val CONVERTIBLE_BOND_INDEX_SECID = "1.000832"
    const val CONVERTIBLE_BOND_INDEX_NAME = "中证转债"

    private val CREDIT_KEYWORDS = listOf("信用", "企债", "产业债", "公司债", "城投")

    /**
     * Resolves the default benchmark only for categories with a published broad index. Stock, mixed
     * and QDII funds return null because their benchmark must come from the data source.
     */
    fun benchmarkSecid(profile: FundProfile): String? = when {
        FundNavEstimator.usesConvertibleBenchmarkRoute(profile) -> CONVERTIBLE_BOND_INDEX_SECID
        FundNavEstimator.usesBondBenchmarkRoute(profile) -> {
            val classification = "${profile.name} ${profile.fundType.orEmpty()}"
            if (CREDIT_KEYWORDS.any { classification.contains(it) }) CORPORATE_BOND_INDEX_SECID
            else TREASURY_INDEX_SECID
        }
        else -> null
    }

    fun benchmarkName(secid: String): String? = when (secid) {
        TREASURY_INDEX_SECID -> TREASURY_INDEX_NAME
        CORPORATE_BOND_INDEX_SECID -> CORPORATE_BOND_INDEX_NAME
        CONVERTIBLE_BOND_INDEX_SECID -> CONVERTIBLE_BOND_INDEX_NAME
        else -> null
    }
}
