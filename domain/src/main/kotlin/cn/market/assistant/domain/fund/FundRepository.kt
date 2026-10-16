package cn.market.assistant.domain.fund

interface FundRepository {
    suspend fun searchFunds(query: String): List<FundSearchResult>

    /** Returns a partial profile when the profile endpoint responds but NAV or holdings are unavailable. */
    suspend fun fundProfile(code: String): FundProfile

    suspend fun cachedNavHistory(code: String, limit: Int? = null): FundNavHistoryCache

    /** Downloads every available NAV page and keeps the previous cache if a page fails. */
    suspend fun backfillFullNavHistory(code: String): FundNavHistorySyncResult

    suspend fun watchlistedFunds(): List<FundWatchlistEntry>

    suspend fun isFundWatchlisted(code: String): Boolean

    suspend fun setFundWatchlisted(
        code: String,
        name: String,
        watched: Boolean,
        category: String = "公募基金"
    )

    /** Refreshes the latest disclosed NAV and merges it into the local history cache. */
    suspend fun refreshFundNav(code: String): FundWatchlistEntry

    /** Stores rolling OOS residuals in the encrypted local ledger for interval calibration. */
    suspend fun saveNavResidualSamples(samples: List<FundNavResidualSample>)

    /** Reads the most recent residuals for one fund and one stable estimate route. */
    suspend fun navResidualSamples(
        code: String,
        routeKey: String,
        limit: Int = 500
    ): List<FundNavResidualSample>

    /** Returns current quote changes for supported disclosed holdings, without extrapolating missing weights. */
    suspend fun refreshHoldingQuotes(holdings: List<FundHolding>): List<FundHoldingQuote>
}
