package com.meda.portico.core.content

/** The hold that confirms a Home button that "asks first" (0.5.10-alpha): one second, in place of a dialog. */
object HoldToConfirm {
    const val HOLD_MS = 1000L
}

/**
 * The state of one hold-to-confirm press, with no clock of its own: every input carries the time [t] (ms, any monotonic
 * origin), so a test drives it with a fake clock and the app with `SystemClock.elapsedRealtime()`. Pure.
 *
 * - [down] starts a hold (a stray second down restarts it); [tick] is the app's frame callback; [up] is the finger lifting;
 *   [cancel] is everything else that ends a touch (moving off the button, ACTION_CANCEL, a scroll takeover, the view going away).
 * - The action fires exactly once per hold: by a [tick] at or after [holdMs], or by an [up] at or after it (a finger lifted
 *   exactly on the second, or a late frame). Nothing after a fire fires again, and nothing after a cancel does either.
 * - A release BEFORE completion is a [Outcome.HINT] (a short tap, or any early lift): the app tells the person to hold. A
 *   [cancel] gives no hint: the finger left on purpose or the touch was taken.
 */
class HoldState(private val holdMs: Long = HoldToConfirm.HOLD_MS) {
    enum class Phase { IDLE, HOLDING, FIRED, CANCELLED }
    enum class Outcome { NONE, FIRE, HINT }

    var phase: Phase = Phase.IDLE
        private set
    private var startedAt = 0L

    fun down(t: Long): Outcome {
        phase = Phase.HOLDING
        startedAt = t
        return Outcome.NONE
    }

    fun tick(t: Long): Outcome {
        if (phase != Phase.HOLDING) return Outcome.NONE
        if (t - startedAt >= holdMs) {
            phase = Phase.FIRED
            return Outcome.FIRE
        }
        return Outcome.NONE
    }

    fun up(t: Long): Outcome {
        if (phase != Phase.HOLDING) return Outcome.NONE // after a fire, a cancel or with no down: the up does nothing
        if (t - startedAt >= holdMs) {
            phase = Phase.FIRED
            return Outcome.FIRE
        }
        phase = Phase.CANCELLED
        return Outcome.HINT
    }

    fun cancel() {
        if (phase == Phase.HOLDING) phase = Phase.CANCELLED
    }

    /** The fill, 0..1: how far the hold has got. 0 when not holding (idle, cancelled), 1 once fired. Never past 1, never below 0. */
    fun progress(t: Long): Float = when (phase) {
        Phase.IDLE, Phase.CANCELLED -> 0f
        Phase.FIRED -> 1f
        Phase.HOLDING -> ((t - startedAt).toFloat() / holdMs).coerceIn(0f, 1f)
    }
}
