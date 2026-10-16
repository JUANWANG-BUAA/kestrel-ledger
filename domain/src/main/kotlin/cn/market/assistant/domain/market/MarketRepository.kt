package cn.market.assistant.domain.market

/** Boundary between features and market data providers/local quote storage. */
interface MarketRepository {
    suspend fun cachedQuotes(): Map<String, MarketQuote>

    suspend fun refreshOverview(): QuoteRefreshResult

    suspend fun refreshGold(): QuoteRefreshResult

    /** Fetches and caches one instrument quote. Throws when no usable source responds. */
    suspend fun refreshQuote(secid: String): MarketQuote

    /** Reads one cached OHLC series and the timestamp of its latest cached bar. */
    suspend fun cachedChart(secid: String, period: ChartPeriod): CachedMarketChart

    /** Fetches and persists an OHLC series, retaining the old cache if the source is empty. */
    suspend fun refreshChart(secid: String, period: ChartPeriod): CachedMarketChart
}
