package com.meda.portico.core.ring

/** Which channel a door ring's notification goes on: pure, so a JVM test pins it. */
object RingAlertChannel {
    /**
     * 0.6.76: Android decides heads-up by the channel's importance and cannot suppress it per notification, so the notification of a ring whose
     * call window is shown directly (unlocked phone, [RingScreenPolicy.Decision.DIRECT_OVERLAY], launch about to be issued) goes on the quiet
     * [Channel.RING_WINDOW]; the window rings itself. Every other case (locked, screen off, no permission, a failed or never-issued launch) keeps
     * [base]: the full-screen intent and the ring channel's own sound.
     */
    fun forRing(base: Channel, decision: RingScreenPolicy.Decision, launchIssued: Boolean): Channel =
        if (base == Channel.RING && decision == RingScreenPolicy.Decision.DIRECT_OVERLAY && launchIssued) Channel.RING_WINDOW else base
}

/** When the call window must stay silent although it owns the ring: the channel's own DND rule. */
object RingSoundPolicy {
    /** `NotificationManager.INTERRUPTION_FILTER_*`: ALL = 1 (DND off), PRIORITY = 2, NONE = 3, ALARMS = 4; UNKNOWN = 0 is treated as DND off. */
    fun silenced(interruptionFilter: Int, channelBypassesDnd: Boolean, channelBlocked: Boolean): Boolean =
        channelBlocked || (interruptionFilter in 2..4 && !channelBypassesDnd)
}

/** What actually rings (the Android Ringtone and Vibrator); fakeable. */
interface RingAlert {
    fun start()
    fun stop()
}

/**
 * Who rings for a call: the call window ([claim]ed by the notifier when it posts on the quiet channel) or the ring channel (not claimed).
 * Also whether the window actually came up, so a launch that was issued but never produced a window (Android 15 blocking a background start
 * without throwing) can be detected and the ring put back on the loud channel. Thread-safe.
 */
class RingSoundOwnership {
    private val claimed = HashSet<String>()
    private val open = HashSet<String>()

    @Synchronized fun claim(callId: String) { claimed.add(callId) }

    @Synchronized fun release(callId: String) { claimed.remove(callId) }

    @Synchronized fun owns(callId: String): Boolean = callId in claimed

    @Synchronized fun windowOpened(callId: String) { open.add(callId) }

    @Synchronized fun windowClosed(callId: String) { open.remove(callId) }

    @Synchronized fun isOpen(callId: String): Boolean = callId in open

    /** True when the quiet channel was chosen for [callId] but no window is up: the ring would be silent. */
    @Synchronized fun shouldFallBack(callId: String): Boolean = callId in claimed && callId !in open
}

/**
 * The call window's ringing rules. [onWindow] is called on every refresh of the window with the call it shows; sound plays once per call,
 * only while the window shows it ringing AND owns it, and never when [silenced] (DND without bypass, channel blocked). [stop] is idempotent.
 */
class RingSoundController(
    private val alert: RingAlert,
    private val owns: (String) -> Boolean,
    private val silenced: () -> Boolean,
) {
    private var playingFor: String? = null
    private var decidedFor: String? = null

    val isPlaying: Boolean get() = playingFor != null

    fun onWindow(callId: String?, ringing: Boolean) {
        if (callId == null || !ringing || !owns(callId)) {
            stop()
            return
        }
        if (decidedFor == callId) return // one decision per call: a refresh never restarts the ring, a DND flip mid-ring changes nothing
        stop()
        decidedFor = callId
        if (silenced()) return
        playingFor = callId
        alert.start()
    }

    /** Idempotent. The decision for the call is kept, so a refresh after the stop does not start it again. */
    fun stop() {
        if (playingFor != null) {
            playingFor = null
            alert.stop()
        }
    }

    /** Window destroyed: stop and forget, so a recreated window decides again. */
    fun reset() {
        stop()
        decidedFor = null
    }
}
