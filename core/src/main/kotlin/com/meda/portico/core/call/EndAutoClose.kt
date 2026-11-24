package com.meda.portico.core.call

/**
 * The in-call screen closes itself [DELAY_MS] after the REMOTE party hangs up (a clean REMOTE_HANGUP, no failure), so a
 * finished call does not sit there until someone presses Close. Everything else that ends (a failure, a lost network, the cap)
 * keeps its screen and its reason. Pure: the activity feeds it every state and a way to schedule; tests feed a fake.
 *
 * Safety: the pending close belongs to one call attempt (key + generation). A newer state of any other kind (a new call, a ring
 * answered, a held call appearing) cancels it, and the fire itself re-checks, so a screen that now shows a new call never closes.
 */
class EndAutoClose(
    private val schedule: (delayMs: Long, run: () -> Unit) -> Cancel,
    private val nowMs: () -> Long,
    private val close: (waitedMs: Long, callId: String?) -> Unit,
    private val delayMs: Long = DELAY_MS,
) {
    fun interface Cancel { fun cancel() }

    private var pending: Cancel? = null
    private var armedGeneration = -1
    private var armedAtMs = 0L
    private var current: CallUiState? = null
    private var held = false

    /** Feed every state the screen is shown. [heldExists]: a held call keeps the screen (its Resume switcher). */
    fun onState(s: CallUiState, heldExists: Boolean) {
        current = s
        held = heldExists
        if (!wanted(s, heldExists)) {
            cancel()
            return
        }
        if (pending != null && armedGeneration == s.generation) return // already counting down for this very end
        cancel()
        armedGeneration = s.generation
        armedAtMs = nowMs()
        pending = schedule(delayMs) { fire(s.generation) }
    }

    /** A touch on the screen, or Close: close at once (if a remote end is pending). Returns whether it closed. */
    fun touched(): Boolean {
        if (pending == null) return false
        return fire(armedGeneration)
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }

    private fun fire(generation: Int): Boolean {
        val s = current ?: return false
        if (generation != armedGeneration || s.generation != generation || !wanted(s, held)) {
            cancel()
            return false
        }
        cancel() // a touch fires early: the scheduled run must not fire again
        close((nowMs() - armedAtMs).coerceAtLeast(0), s.key?.callId)
        return true
    }

    companion object {
        const val DELAY_MS = 3_000L

        fun wanted(s: CallUiState, heldExists: Boolean): Boolean =
            !heldExists && s.snapshot.phase == Phase.ENDED && s.snapshot.endReason == EndReason.REMOTE_HANGUP && s.snapshot.failure == null
    }
}
