package com.meda.portico.ring

import android.app.NotificationManager
import android.content.Context
import com.meda.portico.core.push.PushEnv
import com.meda.portico.core.ring.Channel

/**
 * What the phone allows RIGHT NOW, for one line of the push and ring journal (0.5.12-alpha): is a Portico screen shown,
 * may a full-screen intent show, what the ring channel's importance is, are notifications on, what Do Not Disturb is set
 * to. Read-only: it asks the system and changes nothing. Each field is read on its own, so one refusing leaves the others;
 * a field that cannot be read is null and prints as `?`. Never throws.
 */
object PushEnvProbe {

    fun read(context: Context, uiVisible: Boolean): PushEnv {
        val nm = attempt { context.getSystemService(NotificationManager::class.java) }
        return PushEnv(
            foreground = uiVisible,
            fullScreen = attempt { PhonePermissions.fullScreenAllowed(context) ?: true }, // below Android 14 the manifest grant is enough
            ringChannel = attempt { Channels.state(context, Channel.RING).name.lowercase() },
            ringImportance = attempt { nm?.getNotificationChannel(Channels.ringId(context))?.importance },
            notifications = attempt { nm?.areNotificationsEnabled() },
            dnd = attempt { dndWord(nm?.currentInterruptionFilter) },
        )
    }

    /** `NotificationManager.INTERRUPTION_FILTER_*` as a journal word; null for unknown. */
    fun dndWord(filter: Int?): String? = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
        else -> null
    }

    private inline fun <T> attempt(block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        null
    }
}
