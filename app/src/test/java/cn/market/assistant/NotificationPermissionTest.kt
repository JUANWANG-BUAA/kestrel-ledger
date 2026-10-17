package cn.market.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionTest {
    @Test
    fun android13RequiresTheRuntimePermissionEvenWhenTheChannelIsEnabled() {
        assertTrue(shouldPostNotifications(sdkInt = 33, runtimePermissionGranted = true, notificationsEnabled = false))
        assertFalse(shouldPostNotifications(sdkInt = 33, runtimePermissionGranted = false, notificationsEnabled = true))
    }

    @Test
    fun legacyVersionsUseTheSystemNotificationSwitchInsteadOfAnUnknownPermission() {
        assertTrue(shouldPostNotifications(sdkInt = 32, runtimePermissionGranted = false, notificationsEnabled = true))
        assertFalse(shouldPostNotifications(sdkInt = 32, runtimePermissionGranted = false, notificationsEnabled = false))
    }
}
