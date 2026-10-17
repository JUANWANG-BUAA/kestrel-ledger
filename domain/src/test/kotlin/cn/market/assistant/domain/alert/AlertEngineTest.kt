package cn.market.assistant.domain.alert

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEngineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val morning = Instant.parse("2026-10-16T01:00:00Z") // 09:00 Asia/Shanghai
    private val night = Instant.parse("2026-10-16T15:30:00Z")   // 23:30 Asia/Shanghai

    private fun rule(
        kind: AlertRuleKind,
        threshold: Double,
        targetId: String? = "1.000300",
        targetLabel: String? = "沪深300"
    ) = AlertRule(
        id = "rule-1",
        kind = kind,
        targetId = targetId,
        targetLabel = targetLabel,
        threshold = threshold,
        createdAtMillis = morning.toEpochMilli(),
        updatedAtMillis = morning.toEpochMilli()
    )

    @Test
    fun priceAboveFiresWithSourceTimeAndRedLines() {
        val outcome = AlertEngine.evaluate(
            rule(AlertRuleKind.PRICE_ABOVE, 4_000.0),
            AlertObservation(value = 4_166.01, source = "tencent", observedAtMillis = morning.toEpochMilli()),
            nowMillis = morning.toEpochMilli()
        )

        val fire = outcome.decision as AlertDecision.Fire
        assertTrue(fire.body.contains("4,166.01"))
        assertTrue(fire.body.contains("来源 tencent"))
        assertTrue(fire.body.contains("不构成投资建议"))
        assertTrue(fire.title.contains("提醒"))
        assertEquals(1, outcome.rule.triggerCount)
        assertEquals(morning.toEpochMilli(), outcome.rule.lastFiredAtMillis)
        assertEquals(0, outcome.rule.consecutiveBreaches)
    }

    @Test
    fun belowThresholdStaysSilentAndResetsTheBreachCounter() {
        val outcome = AlertEngine.evaluate(
            rule(AlertRuleKind.PRICE_ABOVE, 4_000.0).copy(consecutiveBreaches = 3),
            AlertObservation(value = 3_900.0),
            nowMillis = morning.toEpochMilli()
        )

        assertEquals(AlertDecision.Stay, outcome.decision)
        assertEquals(0, outcome.rule.consecutiveBreaches)
        assertEquals(0, outcome.rule.triggerCount)
    }

    @Test
    fun debounceRequiresConsecutiveBreachesBeforeFiring() {
        val debounced = rule(AlertRuleKind.DAILY_CHANGE_BELOW, 2.0).copy(requiredBreaches = 2)

        val first = AlertEngine.evaluate(
            debounced,
            AlertObservation(value = -2.4),
            nowMillis = morning.toEpochMilli()
        )
        assertTrue(first.decision is AlertDecision.Suppressed)
        assertTrue((first.decision as AlertDecision.Suppressed).reason.contains("1/2"))
        assertEquals(1, first.rule.consecutiveBreaches)

        val second = AlertEngine.evaluate(
            first.rule,
            AlertObservation(value = -2.6, source = "tencent"),
            nowMillis = morning.toEpochMilli() + 60_000
        )
        assertTrue(second.decision is AlertDecision.Fire)
    }

    @Test
    fun cooldownSuppressesRepeatFires() {
        val fired = rule(AlertRuleKind.PRICE_ABOVE, 4_000.0).copy(
            lastFiredAtMillis = morning.toEpochMilli(),
            cooldownMinutes = 60
        )

        val outcome = AlertEngine.evaluate(
            fired,
            AlertObservation(value = 4_200.0),
            nowMillis = morning.toEpochMilli() + 10 * 60_000
        )

        assertTrue(outcome.decision is AlertDecision.Suppressed)
        assertTrue((outcome.decision as AlertDecision.Suppressed).reason.contains("冷却中"))
        // A later observation beyond the cooldown fires again.
        val later = AlertEngine.evaluate(fired, AlertObservation(value = 4_200.0), nowMillis = morning.toEpochMilli() + 61 * 60_000)
        assertTrue(later.decision is AlertDecision.Fire)
    }

    @Test
    fun quietHoursDeferInsteadOfDropping() {
        val overnight = rule(AlertRuleKind.HOLDING_LOSS_BELOW, 5.0, null, "账户持仓")
            .copy(quietStartHour = 22, quietEndHour = 8)

        val deferred = AlertEngine.evaluate(overnight, AlertObservation(value = -6.5), nowMillis = night.toEpochMilli())
        assertTrue(deferred.decision is AlertDecision.Suppressed)
        assertTrue((deferred.decision as AlertDecision.Suppressed).reason.contains("静默"))
        assertEquals(1, deferred.rule.consecutiveBreaches)

        val daytime = AlertEngine.evaluate(deferred.rule, AlertObservation(value = -6.5), nowMillis = morning.toEpochMilli())
        assertTrue(daytime.decision is AlertDecision.Fire)
    }

    @Test
    fun missingValueSuppressesAndNeverCountsAsZero() {
        val outcome = AlertEngine.evaluate(
            rule(AlertRuleKind.PRICE_BELOW, 900.0).copy(consecutiveBreaches = 2),
            AlertObservation(value = null, source = null),
            nowMillis = morning.toEpochMilli()
        )

        assertTrue(outcome.decision is AlertDecision.Suppressed)
        assertTrue((outcome.decision as AlertDecision.Suppressed).reason.contains("缺少可用数值"))
        assertEquals(0, outcome.rule.consecutiveBreaches)
    }

    @Test
    fun disabledRulesAndKindSemanticsAreRespected() {
        val disabled = AlertEngine.evaluate(
            rule(AlertRuleKind.PRICE_ABOVE, 1.0).copy(enabled = false),
            AlertObservation(value = 100.0),
            nowMillis = morning.toEpochMilli()
        )
        assertTrue(disabled.decision is AlertDecision.Suppressed)

        assertTrue(AlertEngine.breaches(rule(AlertRuleKind.DAILY_CHANGE_BELOW, 2.0), -2.5))
        assertFalse(AlertEngine.breaches(rule(AlertRuleKind.DAILY_CHANGE_BELOW, 2.0), 2.5))
        assertTrue(AlertEngine.breaches(rule(AlertRuleKind.HOLDING_LOSS_BELOW, 5.0), -7.0))
        assertFalse(AlertEngine.breaches(rule(AlertRuleKind.HOLDING_LOSS_BELOW, 5.0), -4.9))
        assertTrue(AlertEngine.breaches(rule(AlertRuleKind.QUOTE_STALE, 72.0), 80.0))
    }

    @Test
    fun quietWindowHandlesWindowsThatCrossMidnight() {
        val overnight = rule(AlertRuleKind.PRICE_ABOVE, 1.0).copy(quietStartHour = 22, quietEndHour = 8)
        val daytime = rule(AlertRuleKind.PRICE_ABOVE, 1.0).copy(quietStartHour = 9, quietEndHour = 12)

        assertTrue(AlertEngine.isQuietHour(overnight, night.toEpochMilli()))
        assertFalse(AlertEngine.isQuietHour(overnight, morning.toEpochMilli()))
        assertTrue(AlertEngine.isQuietHour(daytime, morning.toEpochMilli()))
    }

    @Test
    fun templatesCoverGoldIndexAccountAndStaleness() {
        val templates = AlertTemplates.all(morning.toEpochMilli())

        assertEquals(6, templates.size)
        assertTrue(templates.any { it.kind == AlertRuleKind.PRICE_ABOVE && it.targetId == "GOLD.SGE.AU9999" })
        assertTrue(templates.any { it.kind == AlertRuleKind.QUOTE_STALE })
        val account = templates.first { it.kind == AlertRuleKind.HOLDING_LOSS_BELOW }
        assertEquals(2, account.requiredBreaches)
        assertEquals("静默 22:00–8:00", account.quietWindow)
    }

    @Test
    fun staleObservationIsSuppressedButStalenessRuleStillFires() {
        val staleAt = morning.toEpochMilli() - 73L * 60 * 60 * 1000
        val stalePrice = AlertEngine.evaluate(
            rule(AlertRuleKind.PRICE_ABOVE, 4_000.0),
            AlertObservation(value = 4_166.01, observedAtMillis = staleAt),
            nowMillis = morning.toEpochMilli()
        )
        assertTrue(stalePrice.decision is AlertDecision.Suppressed)
        assertTrue((stalePrice.decision as AlertDecision.Suppressed).reason.contains("72 小时"))
        assertEquals(0, stalePrice.rule.consecutiveBreaches)

        val stalenessRule = AlertEngine.evaluate(
            rule(AlertRuleKind.QUOTE_STALE, 72.0, null, "关键报价"),
            AlertObservation(value = 80.0, observedAtMillis = staleAt),
            nowMillis = morning.toEpochMilli()
        )
        assertTrue(stalenessRule.decision is AlertDecision.Fire)
    }

    @Test
    fun futureObservationIsSuppressed() {
        val future = AlertEngine.evaluate(
            rule(AlertRuleKind.PRICE_ABOVE, 4_000.0),
            AlertObservation(value = 4_166.01, observedAtMillis = morning.toEpochMilli() + 10 * 60_000),
            nowMillis = morning.toEpochMilli()
        )
        assertTrue(future.decision is AlertDecision.Suppressed)
        assertTrue((future.decision as AlertDecision.Suppressed).reason.contains("来自未来"))
    }
}
