package cn.market.assistant.domain.portfolio

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.sign

data class CashFlow(val date: LocalDate, val amount: BigDecimal)

sealed interface XirrResult {
    data class Available(val annualizedRate: Double) : XirrResult
    data class Unavailable(val reason: XirrFailureReason) : XirrResult
}

enum class XirrFailureReason {
    INVALID_CASHFLOWS,
    MISSING_POSITIVE_FLOW,
    MISSING_NEGATIVE_FLOW,
    MULTIPLE_ROOTS,
    NO_CONVERGENCE,
    INCOMPLETE_VALUATION
}

/** Actual/365 money-weighted annual return with same-day aggregation and ambiguous-root detection. */
object XirrCalculator {
    private const val MIN_RATE = -0.9999
    private const val MAX_RATE = 100.0
    private const val ROOT_TOLERANCE = 1e-11
    private const val GRID_STEPS = 2_048

    fun calculate(cashFlows: List<CashFlow>): XirrResult {
        val flows = cashFlows
            .groupBy(CashFlow::date)
            .map { (date, sameDayFlows) -> CashFlow(date, sameDayFlows.fold(BigDecimal.ZERO) { sum, flow -> sum + flow.amount }) }
            .filter { it.amount.signum() != 0 }
            .sortedBy(CashFlow::date)

        val amounts = flows.map { it.amount.toDouble() }
        if (flows.size < 2 || amounts.any { !it.isFinite() || it == 0.0 }) {
            return XirrResult.Unavailable(XirrFailureReason.INVALID_CASHFLOWS)
        }
        if (flows.none { it.amount.signum() < 0 }) {
            return XirrResult.Unavailable(XirrFailureReason.MISSING_NEGATIVE_FLOW)
        }
        if (flows.none { it.amount.signum() > 0 }) {
            return XirrResult.Unavailable(XirrFailureReason.MISSING_POSITIVE_FLOW)
        }

        val firstDate = flows.first().date
        val scale = amounts.maxOf { abs(it) }
        val normalized = flows.mapIndexed { index, flow ->
            TimedFlow(
                years = java.time.temporal.ChronoUnit.DAYS.between(firstDate, flow.date) / 365.0,
                amount = amounts[index] / scale
            )
        }
        val minLogBase = ln1p(MIN_RATE)
        val maxLogBase = ln1p(MAX_RATE)
        val rootCandidates = mutableListOf<Double>()
        var previousX = minLogBase
        var previousValue = npv(previousX, normalized).value
        if (previousValue == 0.0) rootCandidates += previousX

        for (step in 1..GRID_STEPS) {
            val currentX = minLogBase + (maxLogBase - minLogBase) * step / GRID_STEPS
            val currentValue = npv(currentX, normalized).value
            if (currentValue == 0.0) {
                rootCandidates += currentX
            } else if (previousValue != 0.0 && previousValue.sign != currentValue.sign) {
                rootCandidates += bisect(previousX, currentX, previousValue, normalized)
            }
            previousX = currentX
            previousValue = currentValue
        }

        val roots = rootCandidates.sorted().fold(mutableListOf<Double>()) { unique, candidate ->
            if (unique.isEmpty() || abs(unique.last() - candidate) > 1e-7) unique += candidate
            unique
        }
        if (roots.size > 1) return XirrResult.Unavailable(XirrFailureReason.MULTIPLE_ROOTS)
        if (roots.isEmpty()) {
            val newtonRoot = newton(normalized, ln1p(0.1), minLogBase, maxLogBase)
                ?: return XirrResult.Unavailable(XirrFailureReason.NO_CONVERGENCE)
            return XirrResult.Available(exp(newtonRoot) - 1.0)
        }

        val root = newton(normalized, ln1p(0.1), minLogBase, maxLogBase)
            ?.takeIf { abs(it - roots.single()) <= 1e-7 }
            ?: roots.single()
        return XirrResult.Available(exp(root) - 1.0)
    }

    private data class TimedFlow(val years: Double, val amount: Double)
    private data class Npv(val value: Double, val derivative: Double)

    private fun npv(logBase: Double, flows: List<TimedFlow>): Npv {
        val exponents = flows.map { ln(abs(it.amount)) - it.years * logBase }
        val maxExponent = exponents.maxOrNull() ?: return Npv(Double.NaN, Double.NaN)
        var value = 0.0
        var derivative = 0.0
        flows.forEachIndexed { index, flow ->
            val discounted = flow.amount.sign * exp(exponents[index] - maxExponent)
            value += discounted
            derivative -= flow.years * discounted
        }
        return Npv(value, derivative)
    }

    private fun newton(flows: List<TimedFlow>, initial: Double, min: Double, max: Double): Double? {
        var current = initial.coerceIn(min, max)
        repeat(100) {
            val (value, derivative) = npv(current, flows)
            if (abs(value) <= ROOT_TOLERANCE) return current
            if (!derivative.isFinite() || abs(derivative) < 1e-15) return null
            val next = current - value / derivative
            if (!next.isFinite() || next !in min..max) return null
            if (abs(next - current) < 1e-13) {
                return next.takeIf { abs(npv(it, flows).value) <= ROOT_TOLERANCE }
            }
            current = next
        }
        return null
    }

    private fun bisect(left: Double, right: Double, leftValue: Double, flows: List<TimedFlow>): Double {
        var low = left
        var high = right
        var lowValue = leftValue
        repeat(120) {
            val middle = (low + high) / 2.0
            val middleValue = npv(middle, flows).value
            if (abs(middleValue) <= ROOT_TOLERANCE || high - low <= 1e-13) return middle
            if (lowValue.sign != middleValue.sign) {
                high = middle
            } else {
                low = middle
                lowValue = middleValue
            }
        }
        return (low + high) / 2.0
    }
}
