package cn.market.assistant

/**
 * A normal startup with no interrupted migration does not need a second SQLCipher open just to
 * validate the file again; the real helper open will do it. Recovery/validation is required only when
 * one of the temporary/recovery/failed artifacts exists.
 */
internal fun needsRecoveryReconciliation(
    hasTemporary: Boolean,
    hasRecovery: Boolean,
    hasFailed: Boolean
): Boolean = hasTemporary || hasRecovery || hasFailed
