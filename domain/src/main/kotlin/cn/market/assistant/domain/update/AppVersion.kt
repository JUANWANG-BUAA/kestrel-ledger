package cn.market.assistant.domain.update

/**
 * Compares release tags with the installed version. Numeric segments are compared in order; a
 * missing segment counts as 0, and any suffix (-beta, +build) is ignored so tags such as v1.2.0
 * and 1.2 compare consistently.
 */
object AppVersion {
    fun compare(candidate: String, current: String): Int {
        val left = parts(candidate)
        val right = parts(current)
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    private fun parts(value: String): List<Int> = value.trim()
        .removePrefix("v")
        .removePrefix("V")
        .split('.', '-', '+', '_')
        .mapNotNull { segment -> segment.takeWhile(Char::isDigit).takeIf { it.isNotEmpty() }?.toIntOrNull() }
}
