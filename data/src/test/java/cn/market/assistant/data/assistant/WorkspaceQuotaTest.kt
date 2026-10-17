package cn.market.assistant.data.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceQuotaTest {
    @Test
    fun allowsSmallNewReport() {
        assertTrue(WorkspaceQuota.canWrite(totalBytes = 100, fileCount = 2, incomingBytes = 200))
    }

    @Test
    fun rejectsTotalSizeOverTwoMegabytes() {
        assertFalse(
            WorkspaceQuota.canWrite(
                totalBytes = WorkspaceQuota.MAX_TOTAL_BYTES,
                fileCount = 2,
                incomingBytes = 1
            )
        )
    }

    @Test
    fun rejectsTooManyFilesButOverwriteKeepsCount() {
        assertFalse(WorkspaceQuota.canWrite(totalBytes = 0, fileCount = WorkspaceQuota.MAX_FILES, incomingBytes = 1))
        assertTrue(
            WorkspaceQuota.canWrite(
                totalBytes = 100,
                fileCount = WorkspaceQuota.MAX_FILES,
                incomingBytes = 100,
                replacingBytes = 50
            )
        )
    }
}
