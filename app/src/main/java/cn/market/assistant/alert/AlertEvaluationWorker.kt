package cn.market.assistant.alert

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.market.assistant.AlertNotifier
import cn.market.assistant.AlertEvaluationGate
import cn.market.assistant.AlertObservations
import cn.market.assistant.LedgerProvider
import cn.market.assistant.domain.alert.AlertDecision
import cn.market.assistant.domain.alert.AlertEngine
import cn.market.assistant.domain.alert.AlertEvent
import cn.market.assistant.domain.alert.AlertObservation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background alert evaluation over cached quotes and the encrypted ledger only. It never fetches new
 * market data, so a device without connectivity simply suppresses rules instead of firing stale ones.
 */
class AlertEvaluationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        withContext(Dispatchers.IO) {
            AlertEvaluationGate.mutex.withLock {
            AlertNotifier.ensureChannel(applicationContext)
            // Shared with the Activity; the worker must not close it.
            val ledger = LedgerProvider.get(applicationContext)
            val rules = ledger.alertRules().filter { it.enabled }
            if (rules.isEmpty()) {
                Log.i(TAG, "No enabled alert rules; skipping evaluation")
                return@withContext
            }
            val quotes = ledger.marketQuotes()
            val positions = ledger.positions()
            val observations = AlertObservations.build(rules, quotes, positions)
            var fired = 0
            val now = System.currentTimeMillis()
            rules.forEach { rule ->
                val outcome = AlertEngine.evaluate(
                    rule,
                    observations[rule.id] ?: AlertObservation(value = null),
                    now
                )
                if (outcome.rule != rule) ledger.upsertAlertRule(outcome.rule)
                val decision = outcome.decision
                if (decision is AlertDecision.Fire) {
                    val event = AlertEvent(
                        ruleId = rule.id,
                        ruleKind = rule.kind,
                        title = decision.title,
                        body = decision.body,
                        firedAtMillis = now,
                        targetValue = observations[rule.id]?.value
                    )
                    ledger.recordAlertEvent(event)
                    AlertNotifier.post(applicationContext, event, ongoing = false)
                    fired++
                }
                Log.i(TAG, "Evaluated ${rules.size} alert rules; fired $fired")
            }
            }
        }
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.e(TAG, "Alert evaluation failed; WorkManager will retry", error)
        Result.retry()
    }

    private companion object {
        const val TAG = "AlertEvaluation"
    }
}

/** One persistent 30-minute evaluation job; repeated app launches do not duplicate it. */
object AlertEvaluationScheduler {
    private const val UNIQUE_WORK_NAME = "alert_evaluation"
    private const val INITIAL_DELAY_MINUTES = 10L

    /**
     * The first run is delayed so evaluation never competes with the cold-start database open; after
     * that it runs every 30 minutes.
     */
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<AlertEvaluationWorker>(30, TimeUnit.MINUTES)
            .setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
            .addTag(UNIQUE_WORK_NAME)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
