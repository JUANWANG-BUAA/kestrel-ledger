package cn.market.assistant.snapshot

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Installs one persistent daily snapshot job; repeated app launches do not duplicate it. */
object DailyPortfolioSnapshotScheduler {
    private const val UNIQUE_WORK_NAME = "daily_portfolio_snapshot"
    private val shanghaiZone = ZoneId.of("Asia/Shanghai")

    fun ensureScheduled(context: Context) {
        val now = ZonedDateTime.now(shanghaiZone)
        val nextRun = now.toLocalDate().atTime(21, 0).atZone(shanghaiZone)
            .let { if (it.isAfter(now)) it else it.plusDays(1) }
        val initialDelay = Duration.between(now, nextRun).toMillis().coerceAtLeast(0)
        val request = PeriodicWorkRequestBuilder<DailyPortfolioSnapshotWorker>(24, TimeUnit.HOURS, 1, TimeUnit.HOURS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
