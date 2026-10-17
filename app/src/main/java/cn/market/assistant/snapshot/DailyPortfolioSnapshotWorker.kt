package cn.market.assistant.snapshot

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cn.market.assistant.LedgerProvider
import cn.market.assistant.data.portfolio.LocalPortfolioSnapshotRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Captures only locally available valuations. Ledger marks stale or missing quotes explicitly. */
class DailyPortfolioSnapshotWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        withContext(Dispatchers.IO) {
            val today = LocalDate.now(SHANGHAI_ZONE)
            // Shared with the Activity: one encrypted open per process, never closed by a worker.
            val ledger = LedgerProvider.get(applicationContext)
            val snapshot = LocalPortfolioSnapshotRepository(ledger).capture(today)
            Log.i(TAG, "Captured daily portfolio snapshot for $today (${snapshot.positionCount} positions, fresh=${snapshot.valuationFresh})")
        }
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.e(TAG, "Daily portfolio snapshot failed; WorkManager will retry", error)
        Result.retry()
    }

    private companion object {
        const val TAG = "PortfolioSnapshot"
        val SHANGHAI_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}
