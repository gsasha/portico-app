package com.meda.portico.ring

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import com.meda.portico.AppGraph
import com.meda.portico.core.ring.Channel
import com.meda.portico.core.ring.RingEnvWords

/**
 * D-20261121-ee09: reads, at the moment a ring is posted, started or stopped, every setting that decides whether the person HEARS it, and
 * writes it as one Journal line. Observation only: it never throws and changes nothing.
 */
object RingEnvProbe {
    /** The ring channel as the person left it, the ringer mode, Do Not Disturb and the alarm/ring/notification volumes. */
    fun fields(context: Context, channel: Channel = Channel.RING): List<Pair<String, Any?>> = try {
        val nm = context.getSystemService(NotificationManager::class.java)
        val am = context.getSystemService(AudioManager::class.java)
        val ch = nm?.getNotificationChannel(channel.id)
        val uri = ch?.sound
        listOf(
            "ringer" to RingEnvWords.ringer(am?.ringerMode),
            "dnd" to RingEnvWords.dnd(nm?.currentInterruptionFilter),
            "sound" to RingEnvWords.sound(ch != null, uri == null, uri != null && uri == Settings.System.DEFAULT_RINGTONE_URI),
            "usage" to RingEnvWords.usage(ch?.audioAttributes?.usage),
            "imp" to ch?.importance,
            "vib" to ch?.shouldVibrate(),
            "bypass" to ch?.canBypassDnd(),
            "valarm" to am?.getStreamVolume(AudioManager.STREAM_ALARM),
            "valmax" to am?.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            "vring" to am?.getStreamVolume(AudioManager.STREAM_RING),
            "vnotif" to am?.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
        )
    } catch (_: Throwable) {
        emptyList()
    }

    /** One `ui <event>` line: [first] then the probe. Never throws. */
    fun journal(graph: AppGraph, context: Context, event: String, channel: Channel, vararg first: Pair<String, Any?>) {
        try {
            graph.pushJournal.diag("ui", event, first.toList() + fields(context, channel))
        } catch (_: Throwable) {
        }
    }
}
