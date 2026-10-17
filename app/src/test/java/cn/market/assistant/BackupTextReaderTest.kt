package cn.market.assistant

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupTextReaderTest {
    @Test
    fun readsUtf8Backup() {
        assertEquals("账本 backup", readBackupText(ByteArrayInputStream("账本 backup".toByteArray()), maxBytes = 64))
    }

    @Test
    fun rejectsOversizedFileBeforeDecoding() {
        assertThrows(IllegalArgumentException::class.java) {
            readBackupText(ByteArrayInputStream(ByteArray(9)), maxBytes = 8)
        }
    }
}
