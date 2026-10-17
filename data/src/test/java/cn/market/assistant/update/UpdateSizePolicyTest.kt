package cn.market.assistant.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSizePolicyTest {
    @Test
    fun acceptsNormalApkSize() {
        assertTrue(UpdateClient.isAllowedApkSize(1L))
        assertTrue(UpdateClient.isAllowedApkSize(80L * 1024 * 1024))
    }

    @Test
    fun rejectsEmptyOrOversizedApk() {
        assertFalse(UpdateClient.isAllowedApkSize(0L))
        assertFalse(UpdateClient.isAllowedApkSize(-1L))
        assertFalse(UpdateClient.isAllowedApkSize(UpdateClient.MAX_APK_BYTES + 1))
    }
}
