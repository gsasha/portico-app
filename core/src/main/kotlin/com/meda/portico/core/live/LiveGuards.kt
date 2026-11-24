package com.meda.portico.core.live

/**
 * What the phone's own condition says about live video (DEV-713 S2): is it too hot to decode video, and how many videos can its
 * hardware decode at once. Read on the live thread, once per planning pass. The Android adapters are `app/.../live/AndroidLiveGuards`.
 */
interface LiveGuards {
    /** True from Android's `THERMAL_STATUS_SEVERE` until it is back to `LIGHT` or `NONE` ([ThermalGuard]). */
    fun thermalHot(): Boolean

    /** The most live videos to run at once ([DecoderCap]); at least 1. */
    fun maxStreams(): Int

    companion object {
        /** No guard: never hot, no limit. The default of anything that does not wire the Android adapters. */
        val NONE: LiveGuards = object : LiveGuards {
            override fun thermalHot() = false
            override fun maxStreams() = Int.MAX_VALUE
        }
    }
}

/** Where the phone's thermal status comes from (`PowerManager.addThermalStatusListener`, API 29+; a fake in the tests). */
fun interface ThermalSource {
    /** Starts reporting: [onStatus] gets the status now and on every change. Returns what stops it. May throw (the guard then stays "not hot"). */
    fun listen(onStatus: (Int) -> Unit): () -> Unit
}

/**
 * The thermal policy (DEV-713 S2, DEV-713 design 3.1): at `THERMAL_STATUS_SEVERE` (3) or worse every live tile drops to its still, with the
 * notice "Live paused: the phone is hot"; it stays a still until the status is back to `NONE` (0) or `LIGHT` (1). `MODERATE` (2) is the
 * band between: it changes nothing, so a phone that flaps between SEVERE and MODERATE does not flap the video, and one that cools from
 * SEVERE to MODERATE stays paused. A status Android does not define (negative) changes nothing. One Journal line per transition
 * ([LiveLog.thermal]); nothing is written when the state does not change.
 *
 * Pure: it is fed statuses by a [ThermalSource] (or by a test). Thread-safe (the platform's executor feeds it, the live thread reads it).
 */
class ThermalGuard(private val log: LiveLog = LiveLog.NONE) {
    @Volatile
    var hot: Boolean = false
        private set

    private var stop: (() -> Unit)? = null
    private var failed = false

    /** Starts listening to [source]; a second call is ignored. A source that throws leaves the guard "not hot" for good (live is never blocked by a broken probe) and is tried once. */
    @Synchronized
    fun attach(source: ThermalSource) {
        if (stop != null || failed) return
        stop = try {
            source.listen(::onStatus)
        } catch (e: Throwable) {
            failed = true
            log.fault("thermal", e.javaClass.simpleName)
            return
        }
    }

    @Synchronized
    fun detach() {
        val s = stop ?: return
        stop = null
        try {
            s()
        } catch (_: Throwable) {
            // already gone
        }
    }

    fun onStatus(status: Int) {
        val change: Boolean
        synchronized(this) {
            val next = when {
                status < NONE -> hot
                status >= SEVERE -> true
                status <= LIGHT -> false
                else -> hot // MODERATE: the band between, nothing changes
            }
            change = next != hot
            hot = next
        }
        if (change) {
            try {
                log.thermal(status, hot)
            } catch (_: Throwable) {
                // the Journal never costs the policy
            }
        }
    }

    companion object {
        const val NONE = 0
        const val LIGHT = 1
        const val MODERATE = 2
        const val SEVERE = 3

        /** The word for the Journal: Android's name for the status (`severe`), or `other` for a number it does not define. */
        fun word(status: Int): String = when (status) {
            0 -> "none"
            1 -> "light"
            2 -> "moderate"
            3 -> "severe"
            4 -> "critical"
            5 -> "emergency"
            6 -> "shutdown"
            else -> "other"
        }
    }
}

/**
 * The decoder-instance policy (DEV-713 S2): a phone's hardware H.264 decoder can run only so many instances at once
 * (`MediaCodecInfo.CodecCapabilities.getMaxSupportedInstances`, API 23; low-end phones 2 to 4). The app runs at most (that number minus
 * one) live videos, never fewer than one, keeping one instance free for whatever else the phone is decoding. There is no product cap
 * beyond this (the owner, decision Q4b). Every live stream counts, RTSP (`MediaCodec` instances of ours) and WebRTC alike: libwebrtc's own
 * decoders come from the same pool, and counting a stream whose decoder is software only makes the limit safer.
 */
object DecoderCap {
    /** What is assumed when the phone does not say (no H.264 decoder entry, the query threw, a nonsense number): 4 instances, so 3 videos. */
    const val UNKNOWN_MAX_INSTANCES = 4

    /** The most live videos to run at once for a phone that reports [maxInstances] (null: it did not say). */
    fun cap(maxInstances: Int?): Int = ((maxInstances?.takeIf { it > 0 } ?: UNKNOWN_MAX_INSTANCES) - 1).coerceAtLeast(1)
}

/**
 * [LiveGuards] from a thermal guard and a decoder probe ([maxInstances] returns the phone's `getMaxSupportedInstances` or null, and may throw).
 * The probe runs once, on the first [maxStreams] (the live thread), and its answer is kept.
 */
class PhoneGuards(
    private val thermal: ThermalGuard,
    private val source: ThermalSource?,
    private val maxInstances: () -> Int?,
    private val log: LiveLog = LiveLog.NONE,
) : LiveGuards {
    private val cap: Int by lazy {
        DecoderCap.cap(
            try {
                maxInstances()
            } catch (e: Throwable) {
                log.fault("decoder-cap", e.javaClass.simpleName)
                null
            },
        )
    }

    override fun thermalHot(): Boolean {
        if (source != null) thermal.attach(source) // first use registers the listener (once)
        return thermal.hot
    }

    override fun maxStreams(): Int = cap
}
