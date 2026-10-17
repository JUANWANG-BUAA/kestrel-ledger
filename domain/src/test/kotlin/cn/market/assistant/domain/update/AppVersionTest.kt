package cn.market.assistant.domain.update

import org.junit.Assert.assertEquals
import org.junit.Test

class AppVersionTest {
    @Test
    fun comparesNumericSegments() {
        assertEquals(1, AppVersion.compare("0.2.0", "0.1.9"))
        assertEquals(-1, AppVersion.compare("1.2", "1.10"))
        assertEquals(0, AppVersion.compare("v0.2.0", "0.2.0"))
        assertEquals(1, AppVersion.compare("0.2", "0.1.9"))
    }

    @Test
    fun ignoresSuffixesAndMissingSegments() {
        assertEquals(0, AppVersion.compare("1.2.0-beta", "1.2"))
        assertEquals(1, AppVersion.compare("1.2.1+build", "1.2"))
        assertEquals(0, AppVersion.compare("v1.0.0", "1.0"))
    }
}
