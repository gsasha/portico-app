package com.meda.portico.core.live

/**
 * 0.6.73: Home's live streams outlive the screen going to the background (onStop, screen off, Home button, another app) for
 * [OffscreenGrace.GRACE_MS] (the one 30 s value, shared with the off-viewport grace), so closing the app and reopening it quickly finds
 * the sessions still running (no renegotiation). Pure: the caller passes the clock (ms), so a test needs no timers.
 *
 * [leave] starts the countdown; [back] says what happened by the time the screen is back: [Back.CANCELLED] (within the grace: nothing
 * was stopped, keep the streams), [Back.EXPIRED] (30 s or more: the streams must be stopped, if the timer has not already done it) or
 * [Back.NONE] (no grace was running). [expire] is the timer firing. [clear] drops it with no stop (the caller stops by itself).
 */
class BackgroundGrace {
    enum class Back { NONE, CANCELLED, EXPIRED }

    enum class Step { START, CANCEL, EXPIRE }

    private var leftAt: Long? = null

    /** The screen left. True when a countdown started now; false when one is already running (the first leave counts). */
    fun leave(now: Long): Boolean {
        if (leftAt != null) return false
        leftAt = now
        return true
    }

    /** True while the countdown runs and has not reached [OffscreenGrace.GRACE_MS]. */
    fun holding(now: Long): Boolean = leftAt?.let { now - it < OffscreenGrace.GRACE_MS } == true

    /** True while a countdown exists, expired or not, until [back], [expire] or [clear] ends it. */
    fun pending(): Boolean = leftAt != null

    /** How long the screen has been away (0 when it is not). */
    fun awayMs(now: Long): Long = leftAt?.let { now - it } ?: 0L

    /** The screen is back at [now]. Ends the countdown. */
    fun back(now: Long): Back {
        val at = leftAt ?: return Back.NONE
        leftAt = null
        return if (now - at < OffscreenGrace.GRACE_MS) Back.CANCELLED else Back.EXPIRED
    }

    /** The timer fired: true when a countdown was running (the caller stops the streams now). */
    fun expire(): Boolean {
        if (leftAt == null) return false
        leftAt = null
        return true
    }

    fun clear() { leftAt = null }

    companion object {
        /** The one Journal body for a step: `live grace start 30s`, `live grace cancel after 10s`, `live grace expire`. */
        fun line(step: Step, awayMs: Long = 0L): String = when (step) {
            Step.START -> "live grace start ${OffscreenGrace.GRACE_MS / 1000}s"
            Step.CANCEL -> "live grace cancel after ${awayMs / 1000}s"
            Step.EXPIRE -> "live grace expire"
        }
    }
}
