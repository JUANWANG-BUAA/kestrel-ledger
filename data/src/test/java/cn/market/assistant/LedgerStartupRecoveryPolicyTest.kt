package cn.market.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerStartupRecoveryPolicyTest {
    @Test
    fun normalStartupSkipsTheSecondValidationOpen() {
        assertFalse(needsRecoveryReconciliation(false, false, false))
    }

    @Test
    fun everyUnfinishedRecoveryArtifactForcesValidation() {
        assertTrue(needsRecoveryReconciliation(true, false, false))
        assertTrue(needsRecoveryReconciliation(false, true, false))
        assertTrue(needsRecoveryReconciliation(false, false, true))
    }
}
