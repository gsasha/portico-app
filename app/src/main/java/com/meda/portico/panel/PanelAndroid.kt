package com.meda.portico.panel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import com.meda.portico.core.panel.PanelStream
import com.meda.portico.core.panel.SavedLevels
import com.meda.portico.core.panel.StreamLevels
import com.meda.portico.core.ring.Channel
import com.meda.portico.core.ring.RingSoundPolicy

/** DEV-807: the platform's stream levels for [com.meda.portico.core.panel.PanelVolumeKeeper]. A refusal (Do Not Disturb policy) is `false`. */
class AndroidStreamLevels(context: Context) : StreamLevels {
    private val am: AudioManager? = context.applicationContext.getSystemService(AudioManager::class.java)

    private fun id(s: PanelStream) = if (s == PanelStream.ALARM) AudioManager.STREAM_ALARM else AudioManager.STREAM_VOICE_CALL

    override fun level(stream: PanelStream): Int? = try { am?.getStreamVolume(id(stream)) } catch (_: Exception) { null }
    override fun max(stream: PanelStream): Int? = try { am?.getStreamMaxVolume(id(stream)) } catch (_: Exception) { null }
    override fun set(stream: PanelStream, level: Int): Boolean = try {
        am?.setStreamVolume(id(stream), level, 0)
        am != null
    } catch (_: Exception) {
        false
    }
}

/** The originals kept across a process kill, as one preference per stream (the level, an int; absent = nothing saved). */
class PrefsSavedLevels(private val prefs: SharedPreferences) : SavedLevels {
    override fun load(): Map<PanelStream, Int> =
        PanelStream.entries.mapNotNull { s -> if (prefs.contains(s.name)) s to prefs.getInt(s.name, 0) else null }.toMap()

    override fun save(levels: Map<PanelStream, Int>) {
        val e = prefs.edit()
        for (s in PanelStream.entries) if (s in levels) e.putInt(s.name, levels.getValue(s)) else e.remove(s.name)
        e.commit() // synchronous: a kill right after a raise must find the original
    }

    companion object {
        const val FILE = "panel_volume"
    }
}

/** The readiness facts of the heartbeat that only Android can answer (design section 9). Each read is total: a failure reads as "not ok". */
object PanelAndroidFacts {
    fun overlayOk(c: Context): Boolean = try { android.provider.Settings.canDrawOverlays(c) } catch (_: Exception) { false }

    fun microphoneOk(c: Context): Boolean = try {
        c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    fun notificationsOk(c: Context): Boolean = try {
        c.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    } catch (_: Exception) {
        false
    }

    fun batteryExempt(c: Context): Boolean = try {
        c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) == true
    } catch (_: Exception) {
        false
    }

    /** Android 11+: the app is exempt from "Pause app activity if unused"; below it there is no such feature, so exempt. */
    fun autoRevokeExempt(c: Context): Boolean = try {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || c.packageManager.isAutoRevokeWhitelisted
    } catch (_: Exception) {
        false
    }

    /**
     * The call window's own rule (section 9): not [RingSoundPolicy.silenced], and the ring channel has a sound (a channel set to "None" plays
     * nothing; a missing channel falls back to the default ringtone and counts as audible).
     */
    fun ringAudible(c: Context): Boolean = try {
        val nm = c.getSystemService(NotificationManager::class.java)
        val ch: NotificationChannel? = nm?.getNotificationChannel(Channel.RING.id)
        val silenced = RingSoundPolicy.silenced(
            interruptionFilter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL,
            channelBypassesDnd = ch?.canBypassDnd() ?: false,
            channelBlocked = ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE,
        )
        !silenced && (ch == null || ch.sound != null)
    } catch (_: Exception) {
        false
    }
}
