package cn.market.assistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import cn.market.assistant.domain.market.MarketBoardSnapshot
import cn.market.assistant.domain.market.MarketBoardType
import cn.market.assistant.domain.market.MarketRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Owns board cache loading, refresh coordination, and UI-ready status for the market-board feature. */
@Stable
internal class MarketBoardFeatureState(
    private val repository: MarketRepository,
    private val scope: CoroutineScope
) {
    var snapshots by mutableStateOf(emptyMap<MarketBoardType, MarketBoardSnapshot>())
        private set
    var loadingTypes by mutableStateOf(emptySet<MarketBoardType>())
        private set
    var message by mutableStateOf("")
        private set

    private var initialized = false

    fun initialize() {
        if (initialized) return
        initialized = true
        scope.launch {
            val cachedSnapshots = buildMap {
                MarketBoardType.entries.forEach { type ->
                    val snapshot = try {
                        repository.cachedMarketBoards(type)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    snapshot?.let { put(type, it) }
                }
            }
            // A manual refresh may finish while the cache is loading. Prefer that newer result.
            snapshots = cachedSnapshots + snapshots

            val now = System.currentTimeMillis() / 1000
            MarketBoardType.entries.forEach { type ->
                val fetchedAt = cachedSnapshots[type]?.fetchedAt ?: 0L
                if (fetchedAt == 0L || now - fetchedAt > CACHE_TTL_SECONDS) refresh(type)
            }
        }
    }

    fun refresh(type: MarketBoardType) {
        if (type in loadingTypes) return
        loadingTypes = loadingTypes + type
        scope.launch {
            try {
                val snapshot = repository.refreshMarketBoards(type)
                snapshots = snapshots + (type to snapshot)
                message = "${type.label}板块已更新 ${snapshot.boards.size} 项 · 东方财富"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val cached = snapshots[type]
                message = if (cached == null) {
                    "${type.label}板块同步失败：${error.userFacingMessage("暂不可用")}"
                } else {
                    "${type.label}板块刷新失败，保留 ${cached.boards.size} 项本机快照"
                }
            } finally {
                loadingTypes = loadingTypes - type
            }
        }
    }

    fun refreshAll() {
        MarketBoardType.entries.forEach(::refresh)
    }

    private companion object {
        const val CACHE_TTL_SECONDS = 15 * 60
    }
}

@Composable
internal fun rememberMarketBoardFeatureState(repository: MarketRepository): MarketBoardFeatureState {
    val scope = rememberCoroutineScope()
    val state = remember(repository, scope) { MarketBoardFeatureState(repository, scope) }
    LaunchedEffect(state) { state.initialize() }
    return state
}
