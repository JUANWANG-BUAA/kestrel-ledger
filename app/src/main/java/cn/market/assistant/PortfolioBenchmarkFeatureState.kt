package cn.market.assistant

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import cn.market.assistant.domain.market.CachedMarketChart
import cn.market.assistant.domain.market.ChartPeriod
import cn.market.assistant.domain.market.MarketRepository
import cn.market.assistant.domain.portfolio.BenchmarkSeries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class PortfolioBenchmarkOption(val secid: String, val name: String)

private val portfolioBenchmarkOptions = listOf(
    "1.000300", "1.000001", "0.399001", "0.399006", "1.000688",
    "100.SPX", "100.NDX", "100.DJIA", "100.HSI", "100.N225", "100.KS11"
).mapNotNull { secid ->
    MarketInstrumentCatalog.byId[secid]?.let { PortfolioBenchmarkOption(it.secid, it.name) }
}

/** Owns persisted benchmark selection and the selected index's cached daily history. */
@Stable
internal class PortfolioBenchmarkFeatureState(
    context: Context,
    private val repository: MarketRepository,
    private val scope: CoroutineScope
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var requestJob: Job? = null
    private var requestGeneration = 0

    val options: List<PortfolioBenchmarkOption> = portfolioBenchmarkOptions

    var selectedId by mutableStateOf(readSelectedId())
        private set
    var chart by mutableStateOf<CachedMarketChart?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val selectedOption: PortfolioBenchmarkOption?
        get() = options.firstOrNull { it.secid == selectedId }

    val series: BenchmarkSeries?
        get() {
            val option = selectedOption ?: return null
            val currentChart = chart ?: return null
            return BenchmarkSeries(
                id = option.secid,
                name = option.name,
                source = currentChart.source ?: "本机缓存（来源未记录）",
                bars = currentChart.bars
            )
        }

    fun select(secid: String?) {
        require(secid == null || options.any { it.secid == secid }) { "不支持的参考基准" }
        if (selectedId == secid) return
        check(preferences.edit().putString(KEY_SELECTED_ID, secid ?: NONE).commit()) { "参考基准保存失败" }
        requestGeneration++
        requestJob?.cancel()
        requestJob = null
        loading = false
        selectedId = secid
        chart = null
        error = null
        if (secid != null) load()
    }

    fun load(forceRefresh: Boolean = false) {
        val requestedId = selectedId ?: return
        if (loading) return
        val generation = ++requestGeneration
        requestJob = scope.launch {
            loading = true
            error = null
            try {
                val cached = runCatching { repository.cachedChart(requestedId, ChartPeriod.DAY) }
                cached.onSuccess { result ->
                    if (selectedId == requestedId && generation == requestGeneration) chart = result
                }.onFailure { failure ->
                    if (selectedId == requestedId && generation == requestGeneration) error = failure.message ?: "读取本地行情失败"
                }

                val current = chart
                val cacheAge = current?.cachedAt?.let { System.currentTimeMillis() - it } ?: Long.MAX_VALUE
                val isStale = current == null || current.bars.isEmpty() || cacheAge < 0 || cacheAge >= CHART_TTL_MILLIS
                if (!forceRefresh && !isStale) return@launch

                try {
                    val refreshed = repository.refreshChart(requestedId, ChartPeriod.DAY)
                    if (selectedId == requestedId && generation == requestGeneration) {
                        chart = refreshed
                        if (refreshed.bars.isEmpty()) error = "数据源暂未返回日线，保留已有缓存"
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (selectedId == requestedId && generation == requestGeneration) error = failure.message ?: "更新参考指数失败，继续使用本机缓存"
                }
            } finally {
                if (generation == requestGeneration) {
                    loading = false
                    requestJob = null
                }
            }
        }
    }

    private fun readSelectedId(): String? = preferences.getString(KEY_SELECTED_ID, NONE)
        ?.takeIf { it != NONE && options.any { option -> option.secid == it } }

    private companion object {
        const val PREFERENCES_NAME = "market_ui_preferences"
        const val KEY_SELECTED_ID = "portfolio_benchmark_id"
        const val NONE = "NONE"
        const val CHART_TTL_MILLIS = 12 * 60 * 60 * 1000L
    }
}

@Composable
internal fun rememberPortfolioBenchmarkFeatureState(
    context: Context,
    repository: MarketRepository
): PortfolioBenchmarkFeatureState {
    val scope = rememberCoroutineScope()
    return remember(context, repository) { PortfolioBenchmarkFeatureState(context, repository, scope) }
}
