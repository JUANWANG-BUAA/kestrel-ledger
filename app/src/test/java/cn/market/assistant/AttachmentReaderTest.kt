package cn.market.assistant

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentReaderTest {
    @Test
    fun readsTextAtTheExactCap() {
        val bytes = "hello".toByteArray(Charsets.UTF_8)
        assertEquals("hello", readUtf8Limited(ByteArrayInputStream(bytes), bytes.size))
    }

    @Test
    fun rejectsTextOneByteOverTheCap() {
        val bytes = "hello".toByteArray(Charsets.UTF_8)
        assertNull(readUtf8Limited(ByteArrayInputStream(bytes), bytes.size - 1))
    }

    @Test
    fun countsUtf8BytesNotCharacters() {
        val text = "养基助手"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(text, readUtf8Limited(ByteArrayInputStream(bytes), bytes.size))
        assertNull(readUtf8Limited(ByteArrayInputStream(bytes), bytes.size - 1))
    }

    @Test
    fun acceptsAnEmptyStream() {
        assertEquals("", readUtf8Limited(ByteArrayInputStream(ByteArray(0)), 8))
    }
}
