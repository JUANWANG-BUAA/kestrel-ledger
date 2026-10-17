package cn.market.assistant.data.market

import android.util.Log
import cn.market.assistant.Ledger
import cn.market.assistant.MarketApi
import cn.market.assistant.MarketInstrumentCatalog
import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.domain.market.DataFetchStatus
import cn.market.assistant.domain.market.DataSourceRun
import cn.market.assistant.domain.market.DailyHistoryCoverage
import cn.market.assistant.domain.market.DailyHistoryGapBackfillResult
import cn.market.assistant.domain.market.MarketBoardMemberSnapshot
import cn.market.assistant.domain.market.MarketBoardSnapshot
import cn.market.assistant.domain.market.MarketBoardType
import cn.market.assistant.domain.market.MarketOhlcBar
import cn.market.assistant.domain.market.MarketRepository
import cn.market.assistant.domain.market.QuoteRefreshResult
import cn.market.assistant.domain.market.CachedMarketChart
import cn.market.assistant.domain.market.ChartPeriod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private const val ADJUSTED_STOCK_BARS_TTL_MILLIS = 6 * 60 * 60 * 1000L
private const val MAX_ADJUSTED_STOCK_BARS_CACHE_SIZE = 256
private const val MIN_ADJUSTED_STOCK_REQUEST_INTERVAL_MILLIS = 300L

private data class AdjustedStockBarsCacheEntry(
    val bars: List<MarketOhlcBar>,
    val cachedAtMillis: Long
)

/** Coordinates independent public sources and the local market quote cache. */
class MarketRepositoryImpl(
    private val ledger: Ledger,
    private val ioDispatcher: CoroutineDispatcher
) : MarketRepository {
    private val adjustedStockBarsCache = ConcurrentHashMap<String, AdjustedStockBarsCacheEntry>()
    private val adjustedStockRequestMutex = Mutex()
    private var nextAdjustedStockRequestAtNanos = 0L

    override suspend fun cachedQuotes(): Map<String, MarketQuote> = withContext(ioDispatcher) {
        ledger.marketQuotes()
    }

    override suspend fun dataSourceRuns(limit: Int): List<DataSourceRun> = withContext(ioDispatcher) {
        ledger.dataSourceRuns(limit)
    }

    override suspend fun dailyHistoryCoverage(): List<DailyHistoryCoverage> = withContext(ioDispatcher) {
        dailyCoverageReports()
    }

    override suspend fun backfillDailyHistoryGaps(secid: String): DailyHistoryGapBackfillResult = withContext(ioDispatcher) {
        val instrument = MarketInstrumentCatalog.byId[secid] ?: error("暂不支持该标的日线回填")
        val calendarReferenceId = calendarReferenceId(instrument.market)
        var calendarRefreshed = false
        if (calendarReferenceId != null) {
            val currentCalendar = normalizedDailyDays(ledger.dailyChartDaysByInstrument()[calendarReferenceId].orEmpty())
            if (currentCalendar.size < CALENDAR_WINDOW_SIZE) {
                val requestedAt = System.currentTimeMillis() / 1000
                try {
                    val response = MarketApi.fetchChartBarsWithSource(calendarReferenceId, ChartPeriod.DAY)
                    if (response.bars.isNotEmpty()) {
                        ledger.saveChartBars(calendarReferenceId, ChartPeriod.DAY, response.bars, source = response.source)
                        calendarRefreshed = true
                    }
                    ledger.recordDataSourceRun(
                        source = response.source,
                        dataset = "chart-day-calendar",
                        status = if (response.bars.isEmpty()) DataFetchStatus.FAILURE else DataFetchStatus.SUCCESS,
                        itemCount = response.bars.size,
                        expectedCount = null,
                        errorCode = if (response.bars.isEmpty()) "EmptyResponse" else null,
                        requestedAt = requestedAt
                    )
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    ledger.recordDataSourceRun(
                        source = "chart-router",
                        dataset = "chart-day-calendar",
                        status = DataFetchStatus.FAILURE,
                        itemCount = 0,
                        errorCode = error.javaClass.simpleName,
                        requestedAt = requestedAt
                    )
                    Log.w("DailyHistory", "Could not refresh reference trading calendar", error)
                }
            }
        }

        val coverage = dailyCoverageReports().firstOrNull { it.instrumentId == secid }
        if (coverage == null || !coverage.calendarAvailable) {
            return@withContext DailyHistoryGapBackfillResult(
                calendarAvailable = false,
                calendarReferenceId = calendarReferenceId,
                calendarRefreshed = calendarRefreshed,
                attemptedSessions = 0,
                fetchedBars = 0,
                remainingMissingSessions = emptyList(),
                source = null
            )
        }
        val missingSessions = coverage.missingSessions
        if (missingSessions.isEmpty()) {
            return@withContext DailyHistoryGapBackfillResult(
                calendarAvailable = true,
                calendarReferenceId = calendarReferenceId,
                calendarRefreshed = calendarRefreshed,
                attemptedSessions = 0,
                fetchedBars = 0,
                remainingMissingSessions = emptyList(),
                source = null
            )
        }

        val requestedAt = System.currentTimeMillis() / 1000
        try {
            val response = MarketApi.fetchChartBarsForDates(secid, missingSessions)
            if (response.bars.isNotEmpty()) ledger.saveChartBars(secid, ChartPeriod.DAY, response.bars, source = response.source)
            ledger.recordDataSourceRun(
                source = response.source,
                dataset = "chart-day-gap",
                status = when {
                    response.bars.isEmpty() -> DataFetchStatus.PARTIAL
                    response.bars.size < missingSessions.size -> DataFetchStatus.PARTIAL
                    else -> DataFetchStatus.SUCCESS
                },
                itemCount = response.bars.size,
                expectedCount = missingSessions.size,
                errorCode = if (response.bars.size < missingSessions.size) "IncompleteRange" else null,
                requestedAt = requestedAt
            )
            val remaining = dailyCoverageReports().firstOrNull { it.instrumentId == secid }?.missingSessions.orEmpty()
            DailyHistoryGapBackfillResult(
                calendarAvailable = true,
                calendarReferenceId = calendarReferenceId,
                calendarRefreshed = calendarRefreshed,
                attemptedSessions = missingSessions.size,
                fetchedBars = response.bars.size,
                remainingMissingSessions = remaining,
                source = response.source
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            ledger.recordDataSourceRun(
                source = "chart-router",
                dataset = "chart-day-gap",
                status = DataFetchStatus.FAILURE,
                itemCount = 0,
                expectedCount = missingSessions.size,
                errorCode = error.javaClass.simpleName,
                requestedAt = requestedAt
            )
            throw error
        }
    }

    override suspend fun refreshOverview(): QuoteRefreshResult = withContext(ioDispatcher) {
        val requestedAt = System.currentTimeMillis() / 1000
        val expectedIds = (MarketInstrumentCatalog.items.map { it.secid } + listOf(
            MarketApi.GOLD_SPOT_ID, MarketApi.GOLD_CNY_GRAM_ID, "GOLD.SGE.AU9999"
        )).toSet()
        try {
            val fetchResult = MarketApi.fetchQuotesWithDiagnostics()
            val fresh = fetchResult.quotes
            val freshIds = fresh.mapTo(mutableSetOf()) { it.secid }
            val missingCount = expectedIds.count { it !in freshIds }
            val status = when {
                fresh.isEmpty() -> DataFetchStatus.FAILURE
                missingCount > 0 -> DataFetchStatus.PARTIAL
                else -> DataFetchStatus.SUCCESS
            }
            fetchResult.providerFetches.forEach { providerFetch ->
                ledger.recordDataSourceRun(
                    source = providerFetch.source,
                    dataset = "market-overview",
                    status = providerFetch.status,
                    itemCount = providerFetch.itemCount,
                    expectedCount = providerFetch.expectedCount,
                    errorCode = providerFetch.errorCode,
                    requestedAt = requestedAt
                )
            }
            ledger.recordDataSourceRun(
                source = "quote-router", dataset = "market-overview", status = status,
                itemCount = freshIds.size, expectedCount = expectedIds.size,
                errorCode = if (fresh.isEmpty()) "EmptyResponse" else null, requestedAt = requestedAt
            )
            if (fresh.isNotEmpty()) ledger.saveMarketQuotes(fresh)
            val cached = ledger.marketQuotes()
            QuoteRefreshResult(
                quotes = cached,
                refreshedCount = fresh.size,
                coverageIncomplete = MarketInstrumentCatalog.overview.any { it.secid !in cached }
            )
        } catch (error: Exception) {
            if (error !is CancellationException) ledger.recordDataSourceRun(
                source = "quote-router", dataset = "market-overview", status = DataFetchStatus.FAILURE,
                itemCount = 0, expectedCount = expectedIds.size, errorCode = error.javaClass.simpleName,
                requestedAt = requestedAt
            )
            throw error
        }
    }

    override suspend fun refreshGold(): QuoteRefreshResult = withContext(ioDispatcher) {
        val providers = listOf(
            "sge-au9999" to { MarketApi.fetchSgeAu9999Quote() },
            "gold-api-xau-usd" to { MarketApi.fetchGoldSpot() },
            "gold-api-xau-cny" to { MarketApi.fetchGoldCnyPerGram() },
            "tencent-etf" to { MarketApi.fetchQuote("1.518880") }
        )
        val requestedAt = System.currentTimeMillis() / 1000
        val outcomes = providers.map { (_, fetch) ->
            try {
                Result.success(fetch())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
        providers.zip(outcomes).forEach { (provider, outcome) ->
            val error = outcome.exceptionOrNull()
            ledger.recordDataSourceRun(
                source = provider.first,
                dataset = "gold-quote",
                status = if (outcome.isSuccess) DataFetchStatus.SUCCESS else DataFetchStatus.FAILURE,
                itemCount = if (outcome.isSuccess) 1 else 0,
                expectedCount = 1,
                errorCode = error?.takeUnless { it is CancellationException }?.javaClass?.simpleName,
                requestedAt = requestedAt
            )
        }
        val fresh = outcomes.mapNotNull { it.getOrNull() }
        if (fresh.isNotEmpty()) {
            ledger.saveMarketQuotes(fresh)
            // Gold providers only publish a latest quote; keep one daily observation for the trend.
            ledger.recordGoldDailyHistory(fresh)
        }
        // The SGE publishes ten years of official daily closes. Import them once so the domestic
        // trend is usable immediately; later days continue to accumulate from local observations.
        if (ledger.goldDailyHistory("sge_cny_gram").size < 2) {
            runCatching { MarketApi.fetchSgeDailyCloses() }.getOrNull()?.takeIf { it.size >= 2 }?.let { closes ->
                ledger.importGoldDailyHistory("sge_cny_gram", "上金所日线（一次性回填）", closes)
            }
        }
        QuoteRefreshResult(
            quotes = ledger.marketQuotes(),
            refreshedCount = fresh.size,
            sourceFailureCount = outcomes.count { it.isFailure }
        ).also { result ->
            outcomes.filter { it.isFailure }.forEach { Log.w("GoldQuote", "A gold quote source failed", it.exceptionOrNull()) }
            if (result.sourceFailureCount == outcomes.size) Log.w("GoldQuote", "No gold quote source refreshed successfully")
        }
    }

    override suspend fun refreshQuote(secid: String): MarketQuote = withContext(ioDispatcher) {
        val requestedAt = System.currentTimeMillis() / 1000
        try {
            MarketApi.fetchQuote(secid).also { quote ->
                ledger.saveMarketQuotes(listOf(quote))
                ledger.recordDataSourceRun(
                    source = quote.source, dataset = "instrument-quote", status = DataFetchStatus.SUCCESS,
                    itemCount = 1, expectedCount = 1, requestedAt = requestedAt
                )
            }
        } catch (error: Exception) {
            if (error !is CancellationException) ledger.recordDataSourceRun(
                source = "quote-router", dataset = "instrument-quote", status = DataFetchStatus.FAILURE,
                itemCount = 0, expectedCount = 1, errorCode = error.javaClass.simpleName, requestedAt = requestedAt
            )
            throw error
        }
    }

    override suspend fun refreshListedFundQuote(secid: String): MarketQuote = withContext(ioDispatcher) {
        val requestedAt = System.currentTimeMillis() / 1000
        try {
            MarketApi.fetchListedFundQuote(secid).also { quote ->
                ledger.saveMarketQuotes(listOf(quote))
                ledger.recordDataSourceRun(
                    source = quote.source,
                    dataset = "listed-fund-market-price",
                    status = DataFetchStatus.SUCCESS,
                    itemCount = 1,
                    expectedCount = 1,
                    requestedAt = requestedAt
                )
            }
        } catch (error: Exception) {
            if (error !is CancellationException) ledger.recordDataSourceRun(
                source = "quote-router",
                dataset = "listed-fund-market-price",
                status = DataFetchStatus.FAILURE,
                itemCount = 0,
                expectedCount = 1,
                errorCode = error.javaClass.simpleName,
                requestedAt = requestedAt
            )
            throw error
        }
    }

    override suspend fun refreshPortfolioEtfQuote(secid: String): MarketQuote = withContext(ioDispatcher) {
        val requestedAt = System.currentTimeMillis() / 1000
        try {
            val quote = MarketApi.fetchListedFundQuote(secid)
            require(quote.name.contains("ETF", ignoreCase = true)) { "该代码返回「${quote.name}」，名称未标为 ETF" }
            ledger.saveMarketQuotes(listOf(quote))
            ledger.recordDataSourceRun(
                source = quote.source,
                dataset = "portfolio-etf-quote",
                status = DataFetchStatus.SUCCESS,
                itemCount = 1,
                expectedCount = 1,
                requestedAt = requestedAt
            )
            quote
        } catch (error: Exception) {
            if (error !is CancellationException) ledger.recordDataSourceRun(
                source = "quote-router",
                dataset = "portfolio-etf-quote",
                status = DataFetchStatus.FAILURE,
                itemCount = 0,
                expectedCount = 1,
                errorCode = error.javaClass.simpleName,
                requestedAt = requestedAt
            )
            throw error
        }
    }

    override suspend fun cachedMarketBoards(type: MarketBoardType): MarketBoardSnapshot? = withContext(ioDispatcher) {
        ledger.marketBoardSnapshot(type)
    }

    override suspend fun refreshMarketBoards(type: MarketBoardType): MarketBoardSnapshot = withContext(ioDispatcher) {
        val requestedAt = System.currentTimeMillis() / 1000
        val dataset = if (type == MarketBoardType.INDUSTRY) "industry-boards" else "concept-boards"
        try {
            val boards = EastMoneyBoardDataSource.fetch(type)
            val source = boards.firstOrNull()?.source ?: "eastmoney-board"
            val snapshot = MarketBoardSnapshot(
                type = type,
                boards = boards,
                expectedCount = boards.size,
                fetchedAt = boards.maxOfOrNull { it.fetchedAt } ?: requestedAt,
                source = source
            )
            ledger.saveMarketBoardSnapshot(snapshot)
            ledger.recordDataSourceRun(
                source = source, dataset = dataset, status = DataFetchStatus.SUCCESS,
                itemCount = boards.size, expectedCount = snapshot.expectedCount, requestedAt = requestedAt
            )
            snapshot
        } catch (error: Exception) {
            if (error !is CancellationException) ledger.recordDataSourceRun(
                source = "eastmoney-board", dataset = dataset, status = DataFetchStatus.FAILURE,
                itemCount = 0, errorCode = error.javaClass.simpleName, requestedAt = requestedAt
            )
            throw error
        }
    }

    override suspend fun cachedBoardMembers(boardId: String): MarketBoardMemberSnapshot? = withContext(ioDispatcher) {
        ledger.marketBoardMemberSnapshot(boardId)
    }

    override suspend fun refreshBoardMembers(boardId: String, boardName: String): MarketBoardMemberSnapshot =
        withContext(ioDispatcher) {
            val requestedAt = System.currentTimeMillis() / 1000
            try {
                val snapshot = EastMoneyBoardDataSource.fetchMembers(boardId, boardName)
                ledger.saveMarketBoardMemberSnapshot(snapshot)
                ledger.recordDataSourceRun(
                    source = snapshot.source,
                    dataset = "board-members",
                    status = if (snapshot.isTruncated) DataFetchStatus.PARTIAL else DataFetchStatus.SUCCESS,
                    itemCount = snapshot.members.size,
                    expectedCount = snapshot.totalCount,
                    errorCode = if (snapshot.isTruncated) "TruncatedPage" else null,
                    requestedAt = requestedAt
                )
                snapshot
            } catch (error: Exception) {
                if (error !is CancellationException) ledger.recordDataSourceRun(
                    source = "eastmoney-board",
                    dataset = "board-members",
                    status = DataFetchStatus.FAILURE,
                    itemCount = 0,
                    errorCode = error.javaClass.simpleName,
                    requestedAt = requestedAt
                )
                throw error
            }
        }

    override suspend fun cachedChart(secid: String, period: ChartPeriod): CachedMarketChart = withContext(ioDispatcher) {
        CachedMarketChart(
            bars = ledger.chartBars(secid, period),
            cachedAt = ledger.chartBarsCachedAt(secid, period),
            source = ledger.chartBarsSource(secid, period)
        )
    }

    override suspend fun refreshChart(secid: String, period: ChartPeriod): CachedMarketChart = withContext(ioDispatcher) {
        val requestedAt = System.currentTimeMillis() / 1000
        val dataset = "chart-${period.name.lowercase()}"
        try {
            val response = MarketApi.fetchChartBarsWithSource(secid, period)
            val fresh = response.bars
            if (fresh.isNotEmpty()) ledger.saveChartBars(secid, period, fresh, source = response.source)
            ledger.recordDataSourceRun(
                source = response.source,
                dataset = dataset,
                status = if (fresh.isEmpty()) DataFetchStatus.FAILURE else DataFetchStatus.SUCCESS,
                itemCount = fresh.size,
                errorCode = if (fresh.isEmpty()) "EmptyResponse" else null,
                requestedAt = requestedAt
            )
            CachedMarketChart(
                bars = ledger.chartBars(secid, period),
                cachedAt = ledger.chartBarsCachedAt(secid, period),
                refreshed = fresh.isNotEmpty(),
                source = if (fresh.isNotEmpty()) response.source else ledger.chartBarsSource(secid, period)
            )
        } catch (error: Exception) {
            if (error !is CancellationException) ledger.recordDataSourceRun(
                source = "chart-router", dataset = dataset, status = DataFetchStatus.FAILURE,
                itemCount = 0, errorCode = error.javaClass.simpleName, requestedAt = requestedAt
            )
            throw error
        }
    }

    override suspend fun adjustedStockDailyBars(
        secid: String,
        forceRefresh: Boolean
    ): List<MarketOhlcBar> = withContext(ioDispatcher) {
        require(secid.matches(Regex("[01]\\.\\d{6}"))) { "股票代码格式无效" }
        val nowMillis = System.currentTimeMillis()
        val cached = adjustedStockBarsCache[secid]
        if (!forceRefresh && cached != null && nowMillis - cached.cachedAtMillis in 0..ADJUSTED_STOCK_BARS_TTL_MILLIS) {
            return@withContext cached.bars
        }

        var requestedAt = nowMillis / 1000
        try {
            awaitAdjustedStockRequestSlot()
            val requestStartMillis = System.currentTimeMillis()
            val latestCache = adjustedStockBarsCache[secid]
            if (!forceRefresh && latestCache != null &&
                requestStartMillis - latestCache.cachedAtMillis in 0..ADJUSTED_STOCK_BARS_TTL_MILLIS
            ) {
                return@withContext latestCache.bars
            }
            requestedAt = requestStartMillis / 1000
            val bars = MarketApi.fetchAdjustedStockDailyBars(secid)
            check(bars.isNotEmpty()) { "数据源没有返回复权日线" }
            ledger.recordDataSourceRun(
                source = "tencent-adjusted",
                dataset = "fund-holding-daily-adjusted",
                status = DataFetchStatus.SUCCESS,
                itemCount = bars.size,
                expectedCount = null,
                errorCode = null,
                requestedAt = requestedAt
            )
            adjustedStockBarsCache[secid] = AdjustedStockBarsCacheEntry(bars, System.currentTimeMillis())
            if (adjustedStockBarsCache.size > MAX_ADJUSTED_STOCK_BARS_CACHE_SIZE) {
                val expiredBefore = System.currentTimeMillis() - ADJUSTED_STOCK_BARS_TTL_MILLIS
                adjustedStockBarsCache.entries.removeIf { it.value.cachedAtMillis < expiredBefore }
                while (adjustedStockBarsCache.size > MAX_ADJUSTED_STOCK_BARS_CACHE_SIZE) {
                    val oldestKey = adjustedStockBarsCache.entries.minByOrNull { it.value.cachedAtMillis }?.key ?: break
                    adjustedStockBarsCache.remove(oldestKey)
                }
            }
            bars
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            ledger.recordDataSourceRun(
                source = "tencent-adjusted",
                dataset = "fund-holding-daily-adjusted",
                status = DataFetchStatus.FAILURE,
                itemCount = 0,
                expectedCount = null,
                errorCode = error.javaClass.simpleName,
                requestedAt = requestedAt
            )
            throw error
        }
    }

    private suspend fun awaitAdjustedStockRequestSlot() {
        adjustedStockRequestMutex.withLock {
            val nowNanos = System.nanoTime()
            val waitNanos = nextAdjustedStockRequestAtNanos - nowNanos
            if (waitNanos > 0L) delay((waitNanos + 999_999L) / 1_000_000L)
            nextAdjustedStockRequestAtNanos = System.nanoTime() +
                MIN_ADJUSTED_STOCK_REQUEST_INTERVAL_MILLIS * 1_000_000L
        }
    }

    private fun dailyCoverageReports(): List<DailyHistoryCoverage> {
        val rawCoverage = ledger.dailyHistoryCoverage().associateBy { it.instrumentId }
        val dailyDays = ledger.dailyChartDaysByInstrument().mapValues { (_, days) -> normalizedDailyDays(days) }
        return MarketInstrumentCatalog.items.map { instrument ->
            val days = dailyDays[instrument.secid].orEmpty()
            val referenceId = calendarReferenceId(instrument.market)
            val referenceDays = referenceId?.let { dailyDays[it].orEmpty() }.orEmpty()
            val hasCalendar = referenceDays.size >= CALENDAR_WINDOW_SIZE
            val expectedDays = referenceDays.takeLast(CALENDAR_WINDOW_SIZE)
            val missingDays = if (hasCalendar) expectedDays.filterNot { it in days } else emptyList()
            val raw = rawCoverage[instrument.secid]
            DailyHistoryCoverage(
                instrumentId = instrument.secid,
                barCount = days.size,
                firstDay = days.firstOrNull().orEmpty(),
                lastDay = days.lastOrNull().orEmpty(),
                lastFetchedAt = raw?.lastFetchedAt ?: 0L,
                expectedSessions = expectedDays.size,
                missingSessions = missingDays,
                calendarReferenceId = referenceId,
                calendarAvailable = hasCalendar
            )
        }
    }

    private fun normalizedDailyDays(days: List<String>): List<String> = days.mapNotNull(::normalizeMarketDay)
        .distinct().sorted()

    private fun calendarReferenceId(market: String): String? = when (market) {
        "A股", "黄金" -> "1.000001"
        "美股" -> "100.NDX"
        "港股" -> "100.HSI"
        "日股" -> "100.N225"
        "韩股" -> "100.KS11"
        else -> null
    }

    private fun normalizeMarketDay(value: String): String? = runCatching {
        val trimmed = value.trim()
        val date = if (trimmed.length == 8 && trimmed.all(Char::isDigit)) {
            java.time.LocalDate.parse(trimmed, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        } else {
            java.time.LocalDate.parse(trimmed.take(10))
        }
        date.toString()
    }.getOrNull()

    private companion object {
        const val CALENDAR_WINDOW_SIZE = 20
    }
}
