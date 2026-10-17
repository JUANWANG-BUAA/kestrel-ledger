package cn.market.assistant

import cn.market.assistant.domain.assistant.ChatModelException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFacingErrorTest {
    @Test
    fun modelHttpErrorsReachTheUserWithoutExposingCredentialStrings() {
        val key = "apisk-example-secret-value-123456"
        val message = ChatModelException("API Key 无效（HTTP 401）；Authorization: Bearer $key")
            .userFacingMessage("服务未响应")

        assertTrue(message.contains("HTTP 401"))
        assertTrue(message.contains("[已隐藏]"))
        assertTrue(message.contains("[已隐藏 API Key]"))
        assertTrue(!message.contains(key))
    }

    @Test
    fun unknownErrorsKeepTheCallerFallback() {
        assertEquals("服务未响应", IllegalStateException("internal").userFacingMessage("服务未响应"))
    }
}
