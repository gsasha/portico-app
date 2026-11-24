package com.meda.portico.ring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import com.meda.portico.core.ring.Channel
import com.meda.portico.core.ring.RingAlert
import com.meda.portico.core.ring.RingEnvWords
import com.meda.portico.core.ring.RingSoundOwnership
import com.meda.portico.core.ring.RingSoundPolicy

/** 0.6.76: the one process-wide record of which ring the call window rings and which windows are up (see [RingSoundOwnership]). */
object RingWindowSignals {
    val ownership = RingSoundOwnership()
}

/**
 * 0.6.76: the call window's own ringing, used only for a ring whose notification went on the quiet channel (so Android plays nothing and shows
 * no banner). It rings with what the user set on Android's page for the ring channel `portico_ring_v2` ("Door calls"): its sound (`getSound()`,
 * its `getAudioAttributes()`, the alarm stream by default; "None" = no sound) and its vibration (`shouldVibrate()`, `getVibrationPattern()`),
 * looping until [stop]. DND: [silenced] is the channel's own rule ([RingSoundPolicy]): DND on and the channel not allowed to bypass it = silent.
 */
class RingWindowAlert(context: Context) : RingAlert {
    private val app: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var vibrating = false

    private val nm: NotificationManager? get() = app.getSystemService(NotificationManager::class.java)
    private fun channel(): NotificationChannel? = nm?.getNotificationChannel(Channel.RING.id)

    /** Read fresh each time a ring starts (the user may have changed the channel since). */
    fun silenced(): Boolean = try {
        val ch = channel()
        RingSoundPolicy.silenced(
            interruptionFilter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL,
            channelBypassesDnd = ch?.canBypassDnd() ?: false,
            channelBlocked = ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE,
        )
    } catch (_: Throwable) {
        false
    }

    /** DEV-807: this alert holds the alarm stream raised (Panel mode); released once, in [stop]. */
    private var levelHeld = false

    override fun start() {
        try {
            if (!levelHeld) {
                levelHeld = true
                com.meda.portico.PorticoApp.graph(app).panelRingSoundStarted() // a no-op outside Panel mode; its restore is idempotent
            }
        } catch (_: Throwable) {
        }
        try {
            val ch = channel()
            val uri = if (ch == null) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) else ch.sound // null = the user chose "None"
            val attrs = ch?.audioAttributes ?: AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            if (uri != null) {
                val r = RingtoneManager.getRingtone(app, uri)
                if (r != null) {
                    r.audioAttributes = attrs
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) r.isLooping = true // below API 28 the poll replays
                    r.play()
                    ringtone = r
                    handler.postDelayed(poll, POLL_MS)
                }
            }
            if (ch == null || ch.shouldVibrate()) vibrate(ch?.vibrationPattern ?: DEFAULT_PATTERN, attrs)
        } catch (_: Throwable) {
            // a ring that cannot sound still has its window and its notification
        }
        journalSnd(RingEnvWords.START, null)
    }

    /** D-20261121-ee09: one `ui ring-snd` line with what this alert used (playing = a ringtone object is playing; vibrating) and the settings behind it. */
    private fun journalSnd(act: String, why: String?) {
        try {
            val g = com.meda.portico.PorticoApp.graph(app)
            RingEnvProbe.journal(
                g, app, "ring-snd", Channel.RING,
                "act" to act, "who" to "window", "play" to (try { ringtone?.isPlaying ?: false } catch (_: Throwable) { null }), "vibing" to vibrating,
                "silenced" to silenced(), "why" to why?.let { RingEnvWords.word(it) },
            )
        } catch (_: Throwable) {
        }
    }

    override fun stop() {
        if (ringtone != null || vibrating || levelHeld) journalSnd(RingEnvWords.STOP, "stop")
        handler.removeCallbacks(poll)
        if (levelHeld) {
            levelHeld = false
            try { com.meda.portico.PorticoApp.graph(app).panelRingSoundStopped() } catch (_: Throwable) { }
        }
        try {
            ringtone?.stop()
        } catch (_: Throwable) {
        }
        ringtone = null
        if (vibrating) {
            vibrating = false
            try {
                app.getSystemService(Vibrator::class.java)?.cancel()
            } catch (_: Throwable) {
            }
        }
    }

    /** Below API 28 a Ringtone cannot loop by itself: replay it when it has finished. A no-op where it loops. */
    private val poll = object : Runnable {
        override fun run() {
            val r = ringtone ?: return
            try {
                if (!r.isPlaying) r.play()
            } catch (_: Throwable) {
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(pattern: LongArray, attrs: AudioAttributes) {
        val v = app.getSystemService(Vibrator::class.java) ?: return
        if (!v.hasVibrator() || pattern.isEmpty()) return
        v.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs) // repeat from the start until cancel()
        vibrating = true
    }

    private companion object {
        const val POLL_MS = 1_000L
        val DEFAULT_PATTERN = longArrayOf(0, 800, 600, 800, 600)
    }
}
