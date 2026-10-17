package cn.market.assistant

import kotlinx.coroutines.sync.Mutex

/**
 * Serializes foreground alert checks and the WorkManager evaluator in this process. Both read the
 * current rule, decide, then write lastFiredAtMillis; without this gate two evaluators can fire the
 * same rule before either persists the cooldown.
 */
internal object AlertEvaluationGate {
    val mutex = Mutex()
}
