package cn.market.assistant.update

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateTextReaderTest {
    @Test
    fun readsMetadataWithinLimit() {
        assertEquals("{\"tag_name\":\"v1\"}", readUpdateText(ByteArrayInputStream("{\"tag_name\":\"v1\"}".toByteArray()), 64))
    }

    @Test
    fun rejectsOversizedMetadata() {
        assertThrows(IllegalArgumentException::class.java) {
            readUpdateText(ByteArrayInputStream(ByteArray(65)), maxBytes = 64)
        }
    }
}
