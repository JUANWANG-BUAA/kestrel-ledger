package cn.market.assistant.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloadOrderTest {
    private val apk = "https://github.com/o/r/releases/download/v1/app.apk"
    private val mirrors = listOf(
        UpdateClient.Mirror("gh-proxy", "https://gh-proxy.com/"),
        UpdateClient.Mirror("ghfast", "https://ghfast.top/")
    )

    @Test
    fun mirrorsComeBeforeTheOfficialSource() {
        val order = UpdateClient.downloadOrder(apk, mirrors)
        assertEquals("gh-proxy", order.first().first)
        assertEquals("ghfast", order[1].first)
        assertEquals("GitHub 官方", order.last().first)
        assertTrue(order.last().second.startsWith("https://github.com/"))
    }

    @Test
    fun officialIsTheOnlySourceWhenMirrorsAreDisabled() {
        assertEquals(listOf("GitHub 官方" to apk), UpdateClient.downloadOrder(apk, emptyList()))
    }
}
