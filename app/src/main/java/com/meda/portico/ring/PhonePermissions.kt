package com.meda.portico.ring

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import com.meda.portico.core.ring.Channel
import com.meda.portico.core.ring.ChannelState
import com.meda.portico.core.ring.NotifPermission
import com.meda.portico.core.ring.PermissionState
import com.meda.portico.core.ring.PhoneState

/**
 * Reads what the phone allows, for the notifier ([permissionState]) and for Setup ([phoneState]).
 * Read fresh every time: the user changes these in Settings behind the app's back. Nothing is
 * assumed: on a sideloaded install the full-screen-intent grant on Android 14+ is UNVERIFIED (spec
 * §4.4), so it is asked of `canUseFullScreenIntent()` each time.
 */
object PhonePermissions {

    fun permissionState(context: Context): PermissionState {
        val nm = context.getSystemService(NotificationManager::class.java)
        val ring = Channels.state(context, Channel.RING)
        val status = Channels.state(context, Channel.STATUS)
        return PermissionState(
            notificationsEnabled = nm?.areNotificationsEnabled() ?: true,
            ringChannelEnabled = ring != ChannelState.BLOCKED,
            ringChannelHigh = ring != ChannelState.LOWERED,
            statusChannelEnabled = status != ChannelState.BLOCKED,
            fullScreenAllowed = fullScreenAllowed(context) ?: true,
        )
    }

    /** Android 14+: `canUseFullScreenIntent()`; null below 14 (the manifest grant is enough there). */
    fun fullScreenAllowed(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent()
    }

    /** @param askedBefore Portico has shown the POST_NOTIFICATIONS dialog at least once (kept in prefs). */
    fun phoneState(activity: Activity, askedBefore: Boolean): PhoneState {
        val nm = activity.getSystemService(NotificationManager::class.java)
        val granted = Build.VERSION.SDK_INT < 33 ||
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val rationale = Build.VERSION.SDK_INT >= 33 && activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        val filter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        val am = activity.getSystemService(ActivityManager::class.java)
        val pm = activity.getSystemService(PowerManager::class.java)
        return PhoneState(
            notifPermission = NotifPermission.of(Build.VERSION.SDK_INT, granted, askedBefore, rationale),
            notificationsEnabled = nm?.areNotificationsEnabled() ?: true,
            ringChannel = Channels.state(activity, Channel.RING),
            statusChannel = Channels.state(activity, Channel.STATUS),
            ringBypassesDnd = Channels.bypassesDnd(activity, Channel.RING),
            fullScreenAllowed = fullScreenAllowed(activity),
            dndActive = filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
            backgroundRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && am?.isBackgroundRestricted == true,
            ignoringBatteryOptimizations = pm?.isIgnoringBatteryOptimizations(activity.packageName) ?: true,
        )
    }
}
