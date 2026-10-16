package cn.market.assistant.data.fund

import cn.market.assistant.Ledger
import cn.market.assistant.domain.fund.FundProfile
import cn.market.assistant.domain.fund.FundRepository
import cn.market.assistant.domain.fund.FundSearchResult
import cn.market.assistant.domain.fund.FundNavHistorySyncResult
import cn.market.assistant.domain.fund.FundNavResidualSample
import cn.market.assistant.domain.fund.FundHolding
import cn.market.assistant.domain.fund.FundHoldingQuote
import cn.market.assistant.domain.fund.FundWatchlistEntry
import cn.market.assistant.domain.market.DataFetchStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class FundRepositoryImpl(
    private val ledger: Ledger,
    private val ioDispatcher: CoroutineDispatcher
) : FundRepository {
    override suspend fun searchFunds(query: String): List<FundSearchResult> = withContext(ioDispatcher) {
        val requestedAt = System.currentTimeMillis() / 1000
        try {
            EastMoneyFundDataSource.search(query).also { results ->
                ledger.recordDataSourceRun(
                    source = "eastmoney-fund-search",
                    dataset = "fund-search",
                    status = DataFetchStatus.SUCCESS,
                    itemCount = results.size,
                    requestedAt = requestedAt
                )
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            ledger.recordDataSourceRun(
                source = "eastmoney-fund-search",
                dataset = "fund-search",
                status = DataFetchStatus.FAILURE,
                itemCount = 0,
                errorCode = error.javaClass.simpleName,
                requestedAt = requestedAt
            )
            throw error
        }
    }

    override suspend fun fundProfile(code: String): FundProfile = withContext(ioDispatcher) {
        val requestedAt = System.currentTimeMillis() / 1000
        try {
            val profile = EastMoneyFundDataSource.profile(code)
            if (ledger.findAsset(profile.code) != null) {
                profile.fundType?.let { type -> ledger.addFundAsset(profile.code, profile.name, type) }
            }
            if (profile.navHistory.isNotEmpty()) {
                ledger.saveFundNavHistory(profile.code, profile.navHistory, profile.fetchedAt)
            }
            val cached = ledger.fundNavHistory(profile.code, limit = 120)
            recordProfileRun("fund-profile", "fund-profile", 1, 1, null, requestedAt)
            recordProfileRun(
                "tiantianfund-mobile",
                "fund-nav-history",
                if (profile.navHistory.isNotEmpty()) profile.navHistory.size else cached.points.size,
                if (profile.navError == null) null else 120,
                profile.navError?.let { "SourceUnavailable" } ?: if (profile.navHistory.isEmpty()) "EmptyResponse" else null,
                requestedAt
            )
            recordProfileRun(
                "tiantianfund-mobile",
                "fund-holdings",
                profile.holdings.size,
                null,
                profile.holdingsError?.let { "SourceUnavailable" } ?: if (profile.holdings.isEmpty()) "EmptyResponse" else null,
                requestedAt
            )
            profile.copy(navHistory = profile.navHistory.ifEmpty { cached.points })
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            recordProfileRun(
                "tiantianfund-mobile", "fund-profile", 0, 1, error.javaClass.simpleName, requestedAt
            )
            throw error
        }
    }

    override suspend fun cachedNavHistory(code: String, limit: Int?) = withContext(ioDispatcher) {
        ledger.fundNavHistory(code, limit)
    }

    override suspend fun backfillFullNavHistory(code: String): FundNavHistorySyncResult = withContext(ioDispatcher) {
        val requestedAt = System.currentTimeMillis() / 1000
        try {
            val points = EastMoneyFundDataSource.fetchAllNavHistory(code)
            check(points.isNotEmpty()) { "数据源没有返回基金净值历史" }
            ledger.saveFundNavHistory(code, points, requestedAt)
            val cache = ledger.fundNavHistory(code)
            recordProfileRun("tiantianfund-mobile", "fund-nav-history-full", points.size, null, null, requestedAt)
            FundNavHistorySyncResult(cache, points.size, "tiantianfund", requestedAt)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            recordProfileRun(
                "tiantianfund-mobile", "fund-nav-history-full", 0, null, error.javaClass.simpleName, requestedAt
            )
            throw error
        }
    }

    override suspend fun watchlistedFunds(): List<FundWatchlistEntry> = withContext(ioDispatcher) {
        ledger.fundWatchlist().map { asset ->
            val cache = ledger.fundNavHistory(asset.symbol, limit = 1)
            FundWatchlistEntry(
                asset.symbol, asset.name, cache.points.lastOrNull(), cache.totalCount, cache.cachedAt, asset.category
            )
        }
    }

    override suspend fun isFundWatchlisted(code: String): Boolean = withContext(ioDispatcher) {
        ledger.isFundWatched(code)
    }

    override suspend fun setFundWatchlisted(code: String, name: String, watched: Boolean, category: String) = withContext(ioDispatcher) {
        ledger.setFundWatched(code, name, watched, category)
    }

    override suspend fun refreshFundNav(code: String): FundWatchlistEntry = withContext(ioDispatcher) {
        val requestedAt = System.currentTimeMillis() / 1000
        try {
            val point = EastMoneyFundDataSource.fetchLatestNav(code) ?: error("数据源暂未返回最新净值")
            ledger.saveFundNavHistory(code, listOf(point), requestedAt)
            recordProfileRun("tiantianfund-mobile", "fund-nav-history", 1, 1, null, requestedAt)
            val asset = ledger.findAsset(code) ?: error("该基金尚未保存名称")
            val cache = ledger.fundNavHistory(code, limit = 1)
            FundWatchlistEntry(
                asset.symbol, asset.name, cache.points.lastOrNull(), cache.totalCount, cache.cachedAt, asset.category
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            recordProfileRun(
                "tiantianfund-mobile", "fund-nav-history", 0, 1, error.javaClass.simpleName, requestedAt
            )
            throw error
        }
    }

    override suspend fun saveNavResidualSamples(samples: List<FundNavResidualSample>) = withContext(ioDispatcher) {
        ledger.saveFundNavResidualSamples(samples)
        Unit
    }

    override suspend fun navResidualSamples(
        code: String,
        routeKey: String,
        limit: Int
    ): List<FundNavResidualSample> = withContext(ioDispatcher) {
        ledger.fundNavResidualSamples(code, routeKey, limit)
    }

    override suspend fun refreshHoldingQuotes(holdings: List<FundHolding>): List<FundHoldingQuote> = withContext(ioDispatcher) {
        val requestedAt = System.currentTimeMillis() / 1000
        try {
            val quotes = EastMoneyFundDataSource.fetchHoldingQuotes(holdings)
            recordProfileRun(
                source = "eastmoney",
                dataset = "fund-holdings-quotes",
                itemCount = quotes.size,
                expectedCount = holdings.size,
                errorCode = null,
                requestedAt = requestedAt
            )
            quotes
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            recordProfileRun(
                source = "eastmoney",
                dataset = "fund-holdings-quotes",
                itemCount = 0,
                expectedCount = holdings.size,
                errorCode = error.javaClass.simpleName,
                requestedAt = requestedAt
            )
            throw error
        }
    }

    private fun recordProfileRun(
        source: String,
        dataset: String,
        itemCount: Int,
        expectedCount: Int?,
        errorCode: String?,
        requestedAt: Long
    ) {
        val status = when {
            expectedCount != null && itemCount >= expectedCount && errorCode == null -> DataFetchStatus.SUCCESS
            itemCount == 0 && errorCode != null -> DataFetchStatus.FAILURE
            errorCode != null || expectedCount != null && itemCount < expectedCount -> DataFetchStatus.PARTIAL
            itemCount == 0 -> DataFetchStatus.PARTIAL
            else -> DataFetchStatus.SUCCESS
        }
        ledger.recordDataSourceRun(
            source = source,
            dataset = dataset,
            status = status,
            itemCount = itemCount,
            expectedCount = expectedCount,
            errorCode = errorCode,
            requestedAt = requestedAt
        )
    }
}
