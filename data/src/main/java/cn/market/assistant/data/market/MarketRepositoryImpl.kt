package cn.market.assistant.data.market

import android.util.Log
import cn.market.assistant.Ledger
import cn.market.assistant.MarketApi
import cn.market.assistant.MarketInstrumentCatalog
import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.domain.market.MarketRepository
import cn.market.assistant.domain.market.QuoteRefreshResult
import cn.market.assistant.domain.market.CachedMarketChart
import cn.market.assistant.domain.market.ChartPeriod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Coordinates independent public sources and the local market quote cache. */
class MarketRepositoryImpl(
    private val ledger: Ledger,
    private val ioDispatcher: CoroutineDispatcher
) : MarketRepository {
    override suspend fun cachedQuotes(): Map<String, MarketQuote> = withContext(ioDispatcher) {
        ledger.marketQuotes()
    }

    override suspend fun refreshOverview(): QuoteRefreshResult = withContext(ioDispatcher) {
        val fresh = MarketApi.fetchQuotes()
        if (fresh.isNotEmpty()) ledger.saveMarketQuotes(fresh)
        val cached = ledger.marketQuotes()
        QuoteRefreshResult(
            quotes = cached,
            refreshedCount = fresh.size,
            coverageIncomplete = MarketInstrumentCatalog.overview.any { it.secid !in cached }
        )
    }

    override suspend fun refreshGold(): QuoteRefreshResult = withContext(ioDispatcher) {
        val outcomes = listOf(
            runCatching { MarketApi.fetchSgeAu9999Quote() },
            runCatching { MarketApi.fetchGoldSpot() },
            runCatching { MarketApi.fetchGoldCnyPerGram() },
            runCatching { MarketApi.fetchQuote("1.518880") }
        )
        val fresh = outcomes.mapNotNull { it.getOrNull() }
        if (fresh.isNotEmpty()) ledger.saveMarketQuotes(fresh)
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
        MarketApi.fetchQuote(secid).also { ledger.saveMarketQuotes(listOf(it)) }
    }

    override suspend fun cachedChart(secid: String, period: ChartPeriod): CachedMarketChart = withContext(ioDispatcher) {
        CachedMarketChart(
            bars = ledger.chartBars(secid, period),
            cachedAt = ledger.chartBarsCachedAt(secid, period)
        )
    }

    override suspend fun refreshChart(secid: String, period: ChartPeriod): CachedMarketChart = withContext(ioDispatcher) {
        val fresh = MarketApi.fetchChartBars(secid, period)
        if (fresh.isNotEmpty()) ledger.saveChartBars(secid, period, fresh)
        CachedMarketChart(
            bars = ledger.chartBars(secid, period),
            cachedAt = ledger.chartBarsCachedAt(secid, period),
            refreshed = fresh.isNotEmpty()
        )
    }
}
