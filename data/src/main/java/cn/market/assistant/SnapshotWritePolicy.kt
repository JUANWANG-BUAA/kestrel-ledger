package cn.market.assistant

/** Last-writer-wins guards for snapshot and quote rows. ISO-8601 Instant strings sort correctly. */
internal fun shouldReplaceSnapshot(
    existingObservedAt: String?,
    existingValid: Boolean,
    incomingObservedAt: String
): Boolean {
    if (!existingValid) return true
    if (existingObservedAt.isNullOrBlank()) return true
    return incomingObservedAt >= existingObservedAt
}

internal fun shouldReplaceQuote(existingQuoteTime: Long?, incomingQuoteTime: Long): Boolean =
    existingQuoteTime == null || incomingQuoteTime >= existingQuoteTime
