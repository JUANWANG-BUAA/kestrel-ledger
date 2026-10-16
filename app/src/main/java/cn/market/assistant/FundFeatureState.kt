package cn.market.assistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import cn.market.assistant.domain.fund.FundProfile
import cn.market.assistant.domain.fund.FundNavHistoryCache
import cn.market.assistant.domain.fund.FundNavEstimate
import cn.market.assistant.domain.fund.FundNavEstimator
import cn.market.assistant.domain.fund.FundEstimateMethod
import cn.market.assistant.domain.fund.FundMoneyYieldEstimate
import cn.market.assistant.domain.fund.FundMoneyYieldEstimator
import cn.market.assistant.domain.fund.FundListedPriceAnalytics
import cn.market.assistant.domain.fund.FundMarketPriceComparison
import cn.market.assistant.domain.fund.FundRepository
import cn.market.assistant.domain.fund.FundSearchResult
import cn.market.assistant.domain.market.ChartPeriod
import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.domain.market.MarketRepository
import cn.market.assistant.domain.portfolio.AssetInfo
import cn.market.assistant.domain.portfolio.AssetKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.ZoneId

private const val MAX_HOLDING_QUOTE_AGE_SECONDS = 36 * 60 * 60L
private val marketTimeZone: ZoneId = ZoneId.of("Asia/Shanghai")

@Stable
internal class FundFeatureState(
    private val repository: FundRepository,
    private val marketRepository: MarketRepository,
    private val scope: CoroutineScope
) {
    var results by mutableStateOf(emptyList<FundSearchResult>())
        private set
    var completedQuery by mutableStateOf("")
        private set
    var isSearching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    var profileTarget by mutableStateOf<FundSearchResult?>(null)
        private set
    var profile by mutableStateOf<FundProfile?>(null)
        private set
    var isProfileLoading by mutableStateOf(false)
        private set
    var profileError by mutableStateOf<String?>(null)
        private set
    var navHistoryCache by mutableStateOf<FundNavHistoryCache?>(null)
        private set
    var isBackfillingHistory by mutableStateOf(false)
        private set
    var historySyncMessage by mutableStateOf<String?>(null)
        private set
    var isWatchlisted by mutableStateOf(false)
        private set
    var watchlistMessage by mutableStateOf<String?>(null)
        private set
    var navEstimate by mutableStateOf<FundNavEstimate?>(null)
        private set
    var isEstimatingNav by mutableStateOf(false)
        private set
    var moneyYieldEstimate by mutableStateOf<FundMoneyYieldEstimate?>(null)
        private set
    var listedPriceComparison by mutableStateOf<FundMarketPriceComparison?>(null)
        private set
    var isRefreshingListedPrice by mutableStateOf(false)
        private set

    private var query = ""
    private var searchJob: Job? = null
    private var profileJob: Job? = null
    private var estimateJob: Job? = null
    private var listedPriceJob: Job? = null
    private var searchGeneration = 0
    private var profileGeneration = 0
    private var estimateGeneration = 0
    private var listedPriceGeneration = 0
    private var loadedListedPriceKey: String? = null

    fun search(value: String, force: Boolean = false) {
        val normalized = value.trim()
        if (!force && normalized == query) return
        query = normalized
        searchGeneration++
        searchJob?.cancel()
        results = emptyList()
        completedQuery = ""
        searchError = null
        isSearching = false
        clearProfile()
        if (normalized.length < MIN_QUERY_LENGTH) return

        val generation = searchGeneration
        isSearching = true
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            if (generation != searchGeneration) return@launch
            try {
                val found = repository.searchFunds(normalized)
                if (generation == searchGeneration) {
                    results = found
                    completedQuery = normalized
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == searchGeneration) {
                    searchError = error.userFacingMessage("数据源暂不可用")
                    completedQuery = normalized
                }
            } finally {
                if (generation == searchGeneration) isSearching = false
            }
        }
    }

    fun retrySearch() = search(query, force = true)

    fun openProfile(result: FundSearchResult) {
        profileTarget = result
        profile = null
        profileError = null
        navHistoryCache = null
        historySyncMessage = null
        watchlistMessage = null
        isWatchlisted = false
        isBackfillingHistory = false
        estimateGeneration++
        estimateJob?.cancel()
        navEstimate = null
        isEstimatingNav = false
        moneyYieldEstimate = null
        listedPriceGeneration++
        listedPriceJob?.cancel()
        listedPriceComparison = null
        isRefreshingListedPrice = false
        loadedListedPriceKey = null
        profileGeneration++
        profileJob?.cancel()
        val generation = profileGeneration
        isProfileLoading = true
        profileJob = scope.launch {
            try {
                val loaded = repository.fundProfile(result.code)
                if (generation == profileGeneration) {
                    profile = loaded
                    moneyYieldEstimate = FundMoneyYieldEstimator.estimate(loaded)
                    navHistoryCache = repository.cachedNavHistory(result.code, limit = 120)
                    isWatchlisted = repository.isFundWatchlisted(result.code)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == profileGeneration) profileError = error.userFacingMessage("基金数据暂不可用")
            } finally {
                if (generation == profileGeneration) isProfileLoading = false
            }
        }
    }

    fun retryProfile() {
        profileTarget?.let(::openProfile)
    }

    fun loadListedMarketPrice(profile: FundProfile, forceRefresh: Boolean = false) {
        val targetCode = profileTarget?.code ?: return
        if (profile.code != targetCode) return
        val secid = FundListedPriceAnalytics.marketSecid(profile.code)
        if (secid == null) {
            listedPriceComparison = null
            isRefreshingListedPrice = false
            loadedListedPriceKey = profile.code
            return
        }
        val cacheKey = "${profile.code}:${profile.navDate.orEmpty()}"
        if (!forceRefresh && loadedListedPriceKey == cacheKey &&
            (isRefreshingListedPrice || listedPriceComparison != null)) return

        loadedListedPriceKey = cacheKey
        listedPriceGeneration++
        val generation = listedPriceGeneration
        listedPriceJob?.cancel()
        isRefreshingListedPrice = true
        listedPriceJob = scope.launch {
            var cachedComparison: FundMarketPriceComparison? = null
            try {
                val cachedQuote = marketRepository.cachedQuotes()[secid]
                cachedComparison = cachedQuote?.let { FundListedPriceAnalytics.compare(profile, it) }
                if (generation == listedPriceGeneration && cachedComparison != null) {
                    listedPriceComparison = cachedComparison
                }

                val quote = marketRepository.refreshListedFundQuote(secid)
                val comparison = FundListedPriceAnalytics.compare(profile, quote)
                if (generation == listedPriceGeneration && profileTarget?.code == targetCode) {
                    listedPriceComparison = comparison
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == listedPriceGeneration && profileTarget?.code == targetCode) {
                    val fallback = cachedComparison ?: FundListedPriceAnalytics.compare(profile, null)
                    listedPriceComparison = fallback.copy(
                        reason = "刷新场内报价失败：${error.userFacingMessage("数据源暂不可用")}"
                    )
                }
            } finally {
                if (generation == listedPriceGeneration && profileTarget?.code == targetCode) {
                    isRefreshingListedPrice = false
                }
            }
        }
    }

    fun estimateNav(profile: FundProfile, indexQuote: MarketQuote?, forceRefresh: Boolean = false) {
        val targetCode = profileTarget?.code ?: return
        if (profile.code != targetCode) return
        estimateGeneration++
        val generation = estimateGeneration
        estimateJob?.cancel()
        val usesBenchmark = FundNavEstimator.usesBenchmarkQuoteRoute(profile)
        val usesDisclosedHoldings = FundNavEstimator.usesDisclosedHoldingsRoute(profile) &&
            profile.holdings.isNotEmpty() && !usesBenchmark
        if (usesDisclosedHoldings) {
            isEstimatingNav = true
            estimateJob = scope.launch {
                try {
                    val historySemaphore = Semaphore(3)
                    val holdingQuotes = repository.refreshHoldingQuotes(profile.holdings)
                    val now = System.currentTimeMillis() / 1000
                    val quoteDay = Instant.ofEpochSecond(now).atZone(marketTimeZone).toLocalDate()
                    val quotesByCode = holdingQuotes.associateBy { it.code }
                    val eligibleHoldings = profile.holdings.filter { holding ->
                        val quote = quotesByCode[holding.code] ?: return@filter false
                        val age = now - quote.quoteTime
                        val observedDay = runCatching {
                            Instant.ofEpochSecond(quote.quoteTime).atZone(marketTimeZone).toLocalDate()
                        }.getOrNull()
                        holding.marketSecid != null && quote.changePct.isFinite() && quote.quoteTime > 0 &&
                            age in 0..MAX_HOLDING_QUOTE_AGE_SECONDS && observedDay == quoteDay
                    }
                    val requestedSecids = mutableSetOf<String>()
                    val holdingBarsTasks = eligibleHoldings.mapNotNull { holding ->
                        val secid = holding.marketSecid ?: return@mapNotNull null
                        if (!requestedSecids.add(secid)) return@mapNotNull null
                        async {
                            val bars = try {
                                historySemaphore.withPermit {
                                    marketRepository.adjustedStockDailyBars(secid, forceRefresh)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                emptyList()
                            }
                            holding.code to bars
                        }
                    }
                    val holdingBars = holdingBarsTasks.awaitAll()
                        .filter { (_, bars) -> bars.isNotEmpty() }
                        .toMap()
                    val estimate = FundNavEstimator.estimate(
                        profile,
                        emptyList(),
                        null,
                        holdingQuotes,
                        holdingBars = holdingBars
                    )
                    val result = persistAndCalibrateNavEstimate(estimate)
                    if (generation == estimateGeneration && profileTarget?.code == targetCode) navEstimate = result
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (generation == estimateGeneration && profileTarget?.code == targetCode) {
                        navEstimate = FundNavEstimator.estimate(profile, emptyList(), null)
                            .copy(reason = error.userFacingMessage("披露重仓行情暂不可用"))
                    }
                } finally {
                    if (generation == estimateGeneration && profileTarget?.code == targetCode) isEstimatingNav = false
                }
            }
            return
        }
        val linkedEtfId = if (profile.trackingIndexSecid == null && FundNavEstimator.usesIndexBenchmarkRoute(profile)) {
            FundListedPriceAnalytics.marketSecid(profile.linkedEtfCode.orEmpty())
        } else null
        val benchmarkId = FundNavEstimator.benchmarkSecid(profile)
        val usesLinkedEtfQuote = linkedEtfId != null && profile.trackingIndexSecid == null && linkedEtfId == benchmarkId
        if (benchmarkId == null || (indexQuote != null && indexQuote.secid != benchmarkId && !forceRefresh)) {
            navEstimate = FundNavEstimator.estimate(profile, emptyList(), indexQuote)
            isEstimatingNav = false
            return
        }
        isEstimatingNav = true
        estimateJob = scope.launch {
            try {
                val quote = if (forceRefresh || indexQuote == null) {
                    if (usesLinkedEtfQuote) {
                        marketRepository.refreshListedFundQuote(benchmarkId)
                    } else {
                        marketRepository.refreshQuote(benchmarkId)
                    }
                } else {
                    indexQuote
                }
                val cached = marketRepository.cachedChart(benchmarkId, ChartPeriod.DAY)
                val cacheAge = System.currentTimeMillis() - cached.cachedAt
                val chart = if (
                    forceRefresh || cached.bars.size < MIN_INDEX_HISTORY || cached.cachedAt <= 0 ||
                    cacheAge > MAX_INDEX_CACHE_AGE_MILLIS
                ) {
                    try {
                        marketRepository.refreshChart(benchmarkId, ChartPeriod.DAY)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        cached
                    }
                } else {
                    cached
                }
                val estimate = FundNavEstimator.estimate(profile, chart.bars, quote)
                val result = persistAndCalibrateNavEstimate(estimate)
                if (generation == estimateGeneration && profileTarget?.code == targetCode) navEstimate = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == estimateGeneration && profileTarget?.code == targetCode) {
                    navEstimate = FundNavEstimator.estimate(profile, emptyList(), indexQuote)
                        .copy(reason = error.userFacingMessage("基准指数历史数据暂不可用"))
                }
            } finally {
                if (generation == estimateGeneration && profileTarget?.code == targetCode) isEstimatingNav = false
            }
        }
    }

    private suspend fun persistAndCalibrateNavEstimate(estimate: FundNavEstimate): FundNavEstimate {
        val routeKey = estimate.calibrationRouteKey ?: return estimate
        return try {
            if (estimate.calibrationSamples.isNotEmpty()) {
                repository.saveNavResidualSamples(estimate.calibrationSamples)
            }
            val supportsIntervalCalibration = when (estimate.method) {
                FundEstimateMethod.INDEX_OLS,
                FundEstimateMethod.LINKED_ETF_OLS,
                FundEstimateMethod.BOND_INDEX_OLS,
                FundEstimateMethod.CONVERTIBLE_INDEX_OLS,
                FundEstimateMethod.QDII_INDEX_OLS,
                FundEstimateMethod.DISCLOSED_HOLDINGS_OLS -> true
                else -> false
            }
            if (!supportsIntervalCalibration || estimate.estimatedChangePct == null) {
                return estimate
            }
            val history = repository.navResidualSamples(estimate.code, routeKey)
            FundNavEstimator.calibrateInterval(estimate, history)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            estimate.copy(
                lowerBoundPct = null,
                upperBoundPct = null,
                intervalCalibration = null,
                reason = listOfNotNull(
                    estimate.reason,
                    "误差区间校准数据暂不可用：${error.userFacingMessage("本地保存失败")}"
                ).joinToString("；")
            )
        }
    }

    fun backfillFullHistory() {
        val target = profileTarget ?: return
        if (isBackfillingHistory) return
        isBackfillingHistory = true
        historySyncMessage = "正在分页读取完整净值历史…"
        scope.launch {
            try {
                val result = repository.backfillFullNavHistory(target.code)
                if (profileTarget?.code == target.code) {
                    navHistoryCache = result.cache
                    historySyncMessage = "已保存 ${result.cache.totalCount} 个净值点（${result.cache.firstDay ?: "—"} 至 ${result.cache.lastDay ?: "—"}）"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (profileTarget?.code == target.code) historySyncMessage = error.userFacingMessage("历史净值补齐失败")
            } finally {
                if (profileTarget?.code == target.code) isBackfillingHistory = false
            }
        }
    }

    fun toggleWatchlist(onChanged: (() -> Unit)? = null) {
        val target = profileTarget ?: return
        val name = profile?.name ?: target.name
        val category = profile?.fundType ?: target.category
        val watched = !isWatchlisted
        watchlistMessage = null
        scope.launch {
            try {
                repository.setFundWatchlisted(target.code, name, watched, category)
                if (profileTarget?.code == target.code) {
                    isWatchlisted = watched
                    watchlistMessage = if (watched) "已加入自选" else "已从自选移除"
                    onChanged?.invoke()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (profileTarget?.code == target.code) watchlistMessage = error.userFacingMessage("自选更新失败")
            }
        }
    }

    fun clearProfile() {
        profileGeneration++
        profileJob?.cancel()
        profileTarget = null
        profile = null
        profileError = null
        navHistoryCache = null
        historySyncMessage = null
        watchlistMessage = null
        isWatchlisted = false
        isBackfillingHistory = false
        estimateGeneration++
        estimateJob?.cancel()
        navEstimate = null
        isEstimatingNav = false
        moneyYieldEstimate = null
        listedPriceGeneration++
        listedPriceJob?.cancel()
        listedPriceComparison = null
        isRefreshingListedPrice = false
        loadedListedPriceKey = null
        isProfileLoading = false
    }

    fun clearSearch() {
        searchGeneration++
        searchJob?.cancel()
        query = ""
        results = emptyList()
        completedQuery = ""
        searchError = null
        isSearching = false
        clearProfile()
    }

    private companion object {
        const val MIN_QUERY_LENGTH = 2
        const val SEARCH_DEBOUNCE_MILLIS = 350L
        const val MIN_INDEX_HISTORY = 80
        const val MAX_INDEX_CACHE_AGE_MILLIS = 12 * 60 * 60 * 1000L
    }
}

@Composable
internal fun rememberFundFeatureState(repository: FundRepository, marketRepository: MarketRepository): FundFeatureState {
    val scope = rememberCoroutineScope()
    return remember(repository, marketRepository, scope) { FundFeatureState(repository, marketRepository, scope) }
}

internal fun AssetInfo.toFundSearchResult(): FundSearchResult? =
    takeIf { kind == AssetKind.FUND && symbol.matches(Regex("\\d{6}")) }
        ?.let { FundSearchResult(symbol, name, category, "本机账本", System.currentTimeMillis() / 1000) }
