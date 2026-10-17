package cn.market.assistant

/** Pure SDK policy for notification permission; kept free of Android APIs for unit tests. */
internal fun shouldPostNotifications(
    sdkInt: Int,
    runtimePermissionGranted: Boolean,
    notificationsEnabled: Boolean
): Boolean = if (sdkInt >= 33) runtimePermissionGranted else notificationsEnabled
