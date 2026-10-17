package cn.market.assistant

import android.app.Application
import android.os.SystemClock
import android.util.Log

/**
 * Starts the encrypted ledger as early as possible so its expensive native open overlaps process
 * startup and the first Compose frame. The Activity still owns retry/error UI; a warm-up failure is
 * swallowed here and surfaced by LedgerStartup.
 */
class MarketApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread({
            val startedAt = SystemClock.elapsedRealtime()
            runCatching { LedgerProvider.get(this) }
                .onSuccess {
                    Log.i("LedgerStartup", "application warmup ready in ${SystemClock.elapsedRealtime() - startedAt}ms")
                }
                .onFailure { Log.w("LedgerStartup", "application warmup failed", it) }
        }, "ledger-warmup").apply {
            isDaemon = true
            start()
        }
    }
}
