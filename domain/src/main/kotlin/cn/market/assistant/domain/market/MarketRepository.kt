package cn.market.assistant.domain.market

/** Boundary between features and market data providers/local quote storage. */
interface MarketRepository {
    suspend fun cachedQuotes(): Map<String, MarketQuote>

    /** Reads recent provider request outcomes without exposing portfolio records. */
    suspend fun dataSourceRuns(limit: Int = 40): List<DataSourceRun>

    /** Reports cached daily bars by public instrument ID and observed date range. */
    suspend fun dailyHistoryCoverage(): List<DailyHistoryCoverage>

    /** Loads a same-market reference calendar when needed, then fetches only missing daily sessions. */
    suspend fun backfillDailyHistoryGaps(secid: String): DailyHistoryGapBackfillResult =
        throw UnsupportedOperationException("Daily gap backfill is not available")

    suspend fun refreshOverview(): QuoteRefreshResult

    suspend fun refreshGold(): QuoteRefreshResult

    /** Fetches and caches one instrument quote. Throws when no usable source responds. */
    suspend fun refreshQuote(secid: String): MarketQuote

    /** Fetches the exact exchange quote for a listed fund and retains the provider's quote timestamp. */
    suspend fun refreshListedFundQuote(secid: String): MarketQuote = refreshQuote(secid)

    /** Fetches an exact-code listed quote and caches it only when the provider identifies it as an ETF. */
    suspend fun refreshPortfolioEtfQuote(secid: String): MarketQuote =
        throw UnsupportedOperationException("Portfolio ETF quote lookup is not available")

    /** Fetches forward-adjusted A-share daily bars for historical fund-holding regression. */
    suspend fun adjustedStockDailyBars(secid: String, forceRefresh: Boolean = false): List<MarketOhlcBar> =
        throw UnsupportedOperationException("Adjusted stock history is not available")

    /** Reads the most recent complete industry/concept board snapshot, if cached. */
    suspend fun cachedMarketBoards(type: MarketBoardType): MarketBoardSnapshot?

    /** Downloads and atomically caches a complete industry/concept board snapshot. */
    suspend fun refreshMarketBoards(type: MarketBoardType): MarketBoardSnapshot

    /** Reads cached constituents for one board, if present. */
    suspend fun cachedBoardMembers(boardId: String): MarketBoardMemberSnapshot? = null

    /** Downloads the largest constituents of one board and caches the snapshot. */
    suspend fun refreshBoardMembers(boardId: String, boardName: String): MarketBoardMemberSnapshot =
        throw UnsupportedOperationException("Board constituents are not available")

    /** Reads one cached OHLC series and the timestamp of its latest cached bar. */
    suspend fun cachedChart(secid: String, period: ChartPeriod): CachedMarketChart

    /** Fetches and persists an OHLC series, retaining the old cache if the source is empty. */
    suspend fun refreshChart(secid: String, period: ChartPeriod): CachedMarketChart
}
