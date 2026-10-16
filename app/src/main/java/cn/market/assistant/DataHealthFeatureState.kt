package cn.market.assistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import cn.market.assistant.domain.market.DailyHistoryCoverage
import cn.market.assistant.domain.market.DataSourceRun
import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.domain.market.MarketRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class HealthCheckSummary(
    val checkedAt: Long,
    val marketStatus: String,
    val goldStatus: String,
    val hasWarnings: Boolean
)

/** Coordinates the data-health route without making its screen or the application route own repository work. */
/** Bounds the blocking provider calls so the health page always recovers. */
private const val HEALTH_CHECK_TIMEOUT_MILLIS = 45_000L

@Stable
internal class DataHealthFeatureState(
    private val repository: MarketRepository,
    private val scope: CoroutineScope
) {
    var dataSourceRuns by mutableStateOf(emptyList<DataSourceRun>())
        private set
    var dailyHistoryCoverage by mutableStateOf(emptyList<DailyHistoryCoverage>())
        private set
    var backfillLoadingIds by mutableStateOf(emptySet<String>())
        private set
    var backfillStatus by mutableStateOf("")
        private set
    var healthChecking by mutableStateOf(false)
        private set
    var healthCheck by mutableStateOf<HealthCheckSummary?>(null)
        private set

    suspend fun reloadDiagnostics() {
        coroutineScope {
            val runsTask = async { capture { repository.dataSourceRuns() } }
            val coverageTask = async { capture { repository.dailyHistoryCoverage() } }
            runsTask.await().onSuccess { dataSourceRuns = it }
            coverageTask.await().onSuccess { dailyHistoryCoverage = it }
        }
    }

    fun runHealthCheck(onQuotesUpdated: (Map<String, MarketQuote>) -> Unit) {
        if (healthChecking) return
        scope.launch {
            healthChecking = true
            try {
                // The provider calls are blocking, so the timeout must cancel only the `await`; a child
                // scope timeout would still wait for the blocking sockets to finish.
                val work = async {
                    coroutineScope {
                        val marketTask = async { capture { repository.refreshOverview() } }
                        val goldTask = async { capture { repository.refreshGold() } }
                        marketTask.await() to goldTask.await()
                    }
                }
                val results = withTimeoutOrNull(HEALTH_CHECK_TIMEOUT_MILLIS) { work.await() }
                capture { repository.cachedQuotes() }.onSuccess(onQuotesUpdated)
                if (results == null) {
                    work.cancel()
                    healthCheck = HealthCheckSummary(
                        checkedAt = System.currentTimeMillis() / 1000,
                        marketStatus = "自检超时：公开数据源在 ${HEALTH_CHECK_TIMEOUT_MILLIS / 1000} 秒内没有全部返回",
                        goldStatus = "继续使用本机缓存；可稍后重试或检查网络",
                        hasWarnings = true
                    )
                    reloadDiagnostics()
                    return@launch
                }
                val (marketResult, goldResult) = results

                val marketStatus = marketResult.fold(
                    onSuccess = { result -> when {
                        result.refreshedCount == 0 -> "没有新行情，继续使用本机缓存"
                        result.coverageIncomplete -> "更新 ${result.refreshedCount} 项，部分预设标的缺少报价"
                        else -> "已更新 ${result.refreshedCount} 项预设行情"
                    } },
                    onFailure = { "请求失败，继续使用本机缓存" }
                )
                val goldStatus = goldResult.fold(
                    onSuccess = { result -> when {
                        result.refreshedCount == 0 -> "没有新金价，继续使用本机缓存"
                        result.sourceFailureCount > 0 -> "更新 ${result.refreshedCount} 项，${result.sourceFailureCount} 个来源暂不可用"
                        else -> "已更新 ${result.refreshedCount} 项黄金行情"
                    } },
                    onFailure = { "请求失败，继续使用本机缓存" }
                )
                val warning = marketResult.isFailure || goldResult.isFailure ||
                    marketResult.getOrNull()?.let { it.refreshedCount == 0 || it.coverageIncomplete } == true ||
                    goldResult.getOrNull()?.let { it.refreshedCount == 0 || it.sourceFailureCount > 0 } == true
                healthCheck = HealthCheckSummary(
                    checkedAt = System.currentTimeMillis() / 1000,
                    marketStatus = marketStatus,
                    goldStatus = goldStatus,
                    hasWarnings = warning
                )
                reloadDiagnostics()
            } finally {
                healthChecking = false
            }
        }
    }

    fun backfillDailyHistory(secid: String) {
        if (secid in backfillLoadingIds) return
        scope.launch {
            backfillLoadingIds = backfillLoadingIds + secid
            try {
                val backfill = repository.backfillDailyHistoryGaps(secid)
                val referenceName = backfill.calendarReferenceId
                    ?.let { MarketInstrumentCatalog.byId[it]?.name ?: it } ?: "同市场指数"
                backfillStatus = when {
                    !backfill.calendarAvailable -> "$secid 无法读取 $referenceName 的最近 20 个交易日，暂不能识别缺口"
                    backfill.attemptedSessions == 0 && backfill.calendarRefreshed ->
                        "$secid 已加载 $referenceName 交易日历；最近 20 个观测交易日没有缺口"
                    backfill.attemptedSessions == 0 -> "$secid 最近 20 个观测交易日已覆盖"
                    backfill.remainingMissingSessions.isEmpty() ->
                        "$secid 已补入 ${backfill.fetchedBars} / ${backfill.attemptedSessions} 根日线，最近 20 日覆盖完整" +
                            (backfill.source?.let { " · $it" } ?: "")
                    else -> "$secid 收到 ${backfill.fetchedBars} / ${backfill.attemptedSessions} 根，仍缺 " +
                        backfill.remainingMissingSessions.joinToString("、") + (backfill.source?.let { " · $it" } ?: "")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                backfillStatus = "$secid 回填失败：${error.userFacingMessage("数据源暂不可用")}"
            } finally {
                backfillLoadingIds = backfillLoadingIds - secid
                if (currentCoroutineContext().isActive) reloadDiagnostics()
            }
        }
    }

    private suspend fun <T> capture(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}

@Composable
internal fun rememberDataHealthFeatureState(repository: MarketRepository): DataHealthFeatureState {
    val scope = rememberCoroutineScope()
    return remember(repository, scope) { DataHealthFeatureState(repository, scope) }
}
