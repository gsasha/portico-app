package com.meda.portico.ring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.provider.Settings
import com.meda.portico.R
import com.meda.portico.core.ring.Channel
import com.meda.portico.core.ring.ChannelState
import com.meda.portico.core.ring.RingChannelIds

/**
 * The two notification channels (P2 plan §3.4), created in `Application.onCreate` and again before
 * every post (cheap; a no-op when they exist).
 *
 * - `portico_ring_v2` (the only ring channel since 0.6.64), IMPORTANCE_HIGH: the default ringtone (changeable on Android's page) on the alarm stream (USAGE_ALARM), a
 *   request to bypass Do Not Disturb (A-03), vibration, public on the lock screen. `portico_ring_v1`
 *   (no bypass, notification-ringtone usage) is retired and deleted at start-up. The ring notification adds FLAG_INSISTENT.
 * - `portico_ring_window` (0.6.76), IMPORTANCE_LOW, no sound, no vibration: the ring's notification while the call window is shown directly, so no
 *   heads-up banner covers the window; the window rings with `portico_ring_v2`'s own sound and vibration ([RingWindowAlert]).
 * - `portico_status_v1`, IMPORTANCE_DEFAULT, no sound and no vibration: visible in the shade (LOW
 *   would hide it under "Silent"), never an alert.
 * - `portico_paused_v1`, IMPORTANCE_LOW, no sound: the ongoing "Portico is paused" notification.
 * - `portico_connection_v1`, IMPORTANCE_LOW, no sound: the ongoing "Stay connected" service notification.
 *
 * Importance is fixed at creation and the user may only lower it. Portico never "repairs" a channel
 * the user lowered or blocked (that is the user's choice; Setup shows it with a button to the
 * channel's settings). If Portico itself ever needs a different importance or sound, the fix is a
 * NEW id plus [Channel.RETIRED_IDS], never an edit of an existing id.
 *
 * Bypass-DND is requested for the ring channel only, at creation (the old app did the same). It is a
 * request: Android lets the user turn "Override Do Not Disturb" off per channel, so [bypassesDnd] reads
 * what the phone says and Setup shows it. Whether a given phone honours the request is UNVERIFIED
 * without a device.
 */
object Channels {

    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // Create the one ring channel first, then delete every other ring-family id (the 0.6.58-0.6.63 mode channels and the
        // A-09 per-sound ones) and the retired ones; never the active id. Idempotent.
        val ringId = ringId(context)
        if (nm.getNotificationChannel(ringId) == null) {
            val ring = NotificationChannel(ringId, context.getString(R.string.channel_ring_name), NotificationManager.IMPORTANCE_HIGH)
            ring.description = context.getString(R.string.channel_ring_description)
            // A-03: as the old app's door channel: ask to bypass Do Not Disturb (fixed at creation; the
            // user can still switch it off, and Setup reads the result) and sound on the alarm stream,
            // so the ringer being on silent does not mute a door call.
            ring.setBypassDnd(Channel.RING.bypassDnd)
            // 0.6.64: ONE fixed ring channel (the pre-0.6.58 "Ring and vibrate" id). The ringtone, vibration and Override Do Not
            // Disturb are set by the user on Android's page for it (Settings > Ring control (Android)); these are only the defaults.
            ring.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            ring.enableVibration(true)
            ring.vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
            ring.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ring)
        }
        for (old in RingChannelIds.staleIds(nm.notificationChannels.map { it.id }, ringId)) nm.deleteNotificationChannel(old)
        // 0.6.76: the quiet channel of a ring whose call window rings (no heads-up banner over the window). LOW, no sound, no vibration. It is not
        // in the ring family, so the cleanup above never lists it; created after that cleanup so it can never be swept by it either.
        if (nm.getNotificationChannel(Channel.RING_WINDOW.id) == null) {
            val w = NotificationChannel(Channel.RING_WINDOW.id, context.getString(R.string.channel_ring_window_name), NotificationManager.IMPORTANCE_LOW)
            w.description = context.getString(R.string.channel_ring_window_description)
            w.setSound(null, null)
            w.enableVibration(false)
            w.setShowBadge(false)
            w.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(w)
        }
        if (nm.getNotificationChannel(Channel.STATUS.id) == null) {
            val status = NotificationChannel(Channel.STATUS.id, context.getString(R.string.channel_status_name), NotificationManager.IMPORTANCE_DEFAULT)
            status.description = context.getString(R.string.channel_status_description)
            status.setSound(null, null)
            status.enableVibration(false)
            status.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(status)
        }
        // Pause: the ongoing "Portico is paused" notification. LOW, no sound, no vibration.
        if (nm.getNotificationChannel(Channel.PAUSED.id) == null) {
            val paused = NotificationChannel(Channel.PAUSED.id, context.getString(R.string.channel_paused_name), NotificationManager.IMPORTANCE_LOW)
            paused.description = context.getString(R.string.channel_paused_description)
            paused.setSound(null, null)
            paused.enableVibration(false)
            paused.setShowBadge(false)
            nm.createNotificationChannel(paused)
        }
        // Stay connected: the ongoing connection-service notification. LOW, no sound, no vibration.
        if (nm.getNotificationChannel(Channel.CONNECTION.id) == null) {
            val c = NotificationChannel(Channel.CONNECTION.id, context.getString(R.string.channel_connection_name), NotificationManager.IMPORTANCE_LOW)
            c.description = context.getString(R.string.channel_connection_description)
            c.setSound(null, null)
            c.enableVibration(false)
            c.setShowBadge(false)
            nm.createNotificationChannel(c)
        }
    }

    /** The live id of a channel (fixed since 0.6.64). */
    fun idOf(context: Context, channel: Channel): String = channel.id

    fun ringId(context: Context): String = Channel.RING.id

    /** The Android settings page of a channel's notification category (its live id). Only the user can switch "Override Do Not Disturb" or raise importance there. */
    fun settingsIntent(context: Context, channel: Channel): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, idOf(context, channel))

    /** `canBypassDnd()` of the channel as it is now; false if it does not exist. */
    fun bypassesDnd(context: Context, channel: Channel): Boolean =
        context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(idOf(context, channel))?.canBypassDnd() ?: false

    /** The channel as the user left it. */
    fun state(context: Context, channel: Channel): ChannelState {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return ChannelState.MISSING
        val ch = nm.getNotificationChannel(idOf(context, channel)) ?: return ChannelState.MISSING
        val floor = when (channel) {
            Channel.RING -> NotificationManager.IMPORTANCE_HIGH
            Channel.STATUS -> NotificationManager.IMPORTANCE_DEFAULT
            Channel.PAUSED, Channel.CONNECTION, Channel.RING_WINDOW -> NotificationManager.IMPORTANCE_LOW
        }
        return when {
            ch.importance == NotificationManager.IMPORTANCE_NONE -> ChannelState.BLOCKED
            ch.importance < floor -> ChannelState.LOWERED
            else -> ChannelState.OK
        }
    }
}
