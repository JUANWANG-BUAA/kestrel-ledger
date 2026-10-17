package cn.market.assistant.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUrlPolicyTest {
    @Test
    fun onlyTrustedHttpsReleaseHostsAreAllowed() {
        assertTrue(isTrustedUpdateUrl("https://api.github.com/repos/a/b/releases/latest"))
        assertTrue(isTrustedUpdateUrl("https://objects.githubusercontent.com/asset.apk"))
        assertFalse(isTrustedUpdateUrl("http://api.github.com/repos/a/b/releases/latest"))
        assertFalse(isTrustedUpdateUrl("https://evil.example/asset.apk"))
        assertFalse(isTrustedUpdateUrl("file:///tmp/asset.apk"))
    }

    @Test
    fun redirectsMustStayOnTrustedHosts() {
        assertEquals(
            "https://objects.githubusercontent.com/x.apk",
            resolveTrustedRedirect("https://api.github.com/repos/a/b", "https://objects.githubusercontent.com/x.apk")
        )
        assertEquals(
            "https://api.github.com/repos/a/b/x",
            resolveTrustedRedirect("https://api.github.com/repos/a/b", "/repos/a/b/x")
        )
        assertNull(resolveTrustedRedirect("https://api.github.com/x", "https://evil.example/x"))
    }
}
