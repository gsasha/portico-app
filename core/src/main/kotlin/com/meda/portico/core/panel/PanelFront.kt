package com.meda.portico.core.panel

/** What the glue (Android) is asked to do after a [PanelFrontMachine] step. */
sealed interface FrontAction {
    /** Nothing to draw or do. */
    data object None : FrontAction

    /** Draw the progress bar: [remainingMs] of [totalMs] left before the return. */
    data class ShowBar(val remainingMs: Long, val totalMs: Long) : FrontAction

    /** No bar (the count is not near, or nothing is counting). */
    data object HideBar : FrontAction

    /** Go back to Fully now: [attempt] 1 is the first try, 2 the one retry. [reason] is why the app was in front. */
    data class ReturnNow(val attempt: Int, val reason: FrontReason) : FrontAction

    /** The return did not take: the app was still in front [PanelFrontMachine.RETURN_CHECK_MS] after it was issued. [willRetry] false = the last one. */
    data class ReturnFailed(val willRetry: Boolean) : FrontAction
}

/**
 * What the machine tells the Journal (0.6.97, the owner 2026-11-21: "it does not auto-close"): the lifecycle of the idle count and of every return
 * countdown, so a test can be read without asking what happened. The glue drains them ([PanelFrontMachine.drainEvents]) and writes one fixed-word
 * `wall idle-*` line each. [kind] is `idle` (opened by hand, the idle timeout), `after-call`, `after-ring` (a ring that ended unanswered) or `close`.
 */
sealed interface FrontEvent {
    /** The idle count started (a hand-opened screen came to the front, or the count was re-armed). [idleMs] <= 0: the idle return is off. */
    data class Armed(val idleMs: Long) : FrontEvent

    /** The visible progress bar appeared: [totalMs] is its length. */
    data class BarStart(val kind: String, val totalMs: Long, val idleMs: Long) : FrontEvent

    /** The count was dropped. [why]: `touch`, `ring`, `call`, `left-front`, `panel-off`. [barShown]: the bar was up. */
    data class Cancelled(val why: String, val kind: String, val barShown: Boolean) : FrontEvent

    /** The count ran out (or Close was pressed): the return is issued now. [sinceTouchMs] is the time since the last touch or since the count began. */
    data class Closing(val why: String, val idleMs: Long, val sinceTouchMs: Long) : FrontEvent
}

/**
 * When Panel mode goes back to Fully (DEV-807, design section 7, "Returning"). One rule keyed on why the app is in front ([FrontReason]):
 *
 * - **ring / call:** the app is in front for a ring or a call. Nothing counts; the idle timeout is suspended.
 * - **a call ends, or a ring ends without an answer** (missed, declined, a house ring included): a [RETURN_BAR_MS] progress bar counts down,
 *   a touch restarts it, then the app returns. A ring that arrives during a hand-opened session turns the reason to `ring`, then `call`, and the
 *   same rule applies after it. The return is keyed on the call ENDING, never on a window closing (the ring window closes at Answer).
 * - **hand:** opened by hand. After the idle timeout ([idleMs]; the last [BAR_LAST_MS] show the bar) with no touch, the app returns.
 * - The mechanism and the check: the glue starts Fully by its launch intent and then `moveTaskToBack(true)`; its own lifecycle is the only signal.
 *   The app still in front [RETURN_CHECK_MS] after the return was issued means it failed: [FrontAction.ReturnFailed], retried once.
 *
 * `overdue` (heartbeat): in front with reason `hand`, and no touch or event for the idle timeout plus [OVERDUE_EXTRA_MS]. HA's backstop uses it.
 *
 * Pure: the clock and the idle setting are injected; the glue calls [update] when the facts change, [touch] on any touch, and [tick] about
 * every 250 ms while the app is in front, and does what [tick] returns. Single-threaded (the main thread).
 */
class PanelFrontMachine(
    private val clockMs: () -> Long,
    /** The idle timeout in ms; zero or less = no idle return (a hand-opened screen then stays). */
    private val idleMs: () -> Long,
) {
    var reason: FrontReason = FrontReason.NONE
        private set

    private var active = false
    private var deadline: Long? = null
    private var countdownTotal = RETURN_BAR_MS
    private var lastActivity = 0L
    private var issuedAt: Long? = null
    private var attempts = 0
    private var returnedReason: FrontReason = FrontReason.NONE
    private var pendingEnd = false
    private var kind = "idle"
    private var barShown = false
    private val events = ArrayList<FrontEvent>()

    /** The events since the last call, oldest first (the glue writes them to the Journal). */
    fun drainEvents(): List<FrontEvent> {
        if (events.isEmpty()) return emptyList()
        val out = events.toList()
        events.clear()
        return out
    }

    /** The count is dropped for [why]: one event, only if something was counting and no return is under way. */
    private fun cancelled(why: String) {
        if (deadline != null && issuedAt == null) events.add(FrontEvent.Cancelled(why, kind, barShown))
        barShown = false
    }

    /** True while a return countdown or an idle count is running. */
    val counting: Boolean get() = deadline != null

    /**
     * The facts changed. [active] is Panel mode on and the app enrolled (the idle timer is suspended while the app is not enrolled).
     * [inFront] is any screen of ours in front; [ringUp] a ring is up; [callLive] a call (placed, Talk, answered, house) is live.
     */
    fun update(active: Boolean, inFront: Boolean, ringUp: Boolean, callLive: Boolean) {
        val now = clockMs()
        val before = reason
        val next = FrontReasons.of(inFront, ringUp, callLive)
        this.active = active
        reason = next
        if (!active || !inFront) {
            // Out of front: nothing counts. A return that was issued has worked (or the app was never in front).
            cancelled(if (!active) "panel-off" else "left-front")
            deadline = null
            if (!inFront) {
                issuedAt = null
                attempts = 0
            }
            return
        }
        when (next) {
            FrontReason.RING, FrontReason.CALL -> {
                cancelled(if (next == FrontReason.CALL) "call" else "ring")
                deadline = null // suspended while a ring, an answer, Talk or a call is up
                lastActivity = now
            }
            FrontReason.HAND -> {
                if (before == FrontReason.RING || before == FrontReason.CALL || pendingEnd) {
                    pendingEnd = false
                    returnedReason = if (before == FrontReason.CALL) FrontReason.CALL else FrontReason.RING
                    startCountdown(now, RETURN_BAR_MS, RETURN_BAR_MS)
                } else if (deadline == null && issuedAt == null) {
                    lastActivity = now
                    startIdle(now)
                }
            }
            FrontReason.NONE -> Unit
        }
    }

    /** A touch anywhere in the app: the count starts over. */
    fun touch() {
        if (!active || reason == FrontReason.NONE) return
        val now = clockMs()
        lastActivity = now
        if (deadline == null || issuedAt != null) return // nothing counts, or a return is under way (a touch does not cancel it)
        if (barShown) events.add(FrontEvent.Cancelled("touch", kind, true))
        barShown = false
        deadline = if (inCountdown) now + RETURN_BAR_MS else now + idleMs().coerceAtLeast(BAR_LAST_MS)
    }

    private var inCountdown = false

    private fun startCountdown(now: Long, total: Long, ms: Long) {
        inCountdown = true
        countdownTotal = total
        deadline = now + ms
        barShown = false
        kind = if (returnedReason == FrontReason.CALL) "after-call" else "after-ring"
    }

    private fun startIdle(now: Long) {
        inCountdown = false
        countdownTotal = BAR_LAST_MS
        val idle = idleMs()
        deadline = if (idle > 0) now + idle else null
        barShown = false
        kind = "idle"
        events.add(FrontEvent.Armed(idle))
    }

    /**
     * A ring window just ended without an answer (the glue knows before the poll of [update] can see it): count the 10 s bar now. While the
     * reason is still ring or call the next [update] starts it.
     */
    fun noteEnded() {
        if (!active) return
        when (reason) {
            FrontReason.HAND -> {
                returnedReason = FrontReason.RING
                startCountdown(clockMs(), RETURN_BAR_MS, RETURN_BAR_MS)
            }
            FrontReason.RING, FrontReason.CALL -> pendingEnd = true
            FrontReason.NONE -> Unit
        }
    }

    /** The person pressed Close: return at once (a failed return is retried like any other). */
    fun closeNow() {
        if (!active || reason == FrontReason.NONE) return
        returnedReason = reason
        deadline = clockMs()
        inCountdown = true
        countdownTotal = RETURN_BAR_MS
        kind = "close"
        barShown = false
    }

    /** What to do now. Cheap; the glue calls it about every 250 ms while the app is in front and acts on the result. */
    fun tick(): FrontAction {
        val now = clockMs()
        val issued = issuedAt
        if (issued != null) {
            if (now - issued < RETURN_CHECK_MS) return FrontAction.None
            // still here RETURN_CHECK_MS after the return was issued: it did not take (a return that works takes the app out of front,
            // and [update] with inFront false clears [issuedAt] before this runs)
            return if (attempts < 2) {
                attempts++
                issuedAt = now
                FrontAction.ReturnFailed(willRetry = true)
            } else {
                issuedAt = null
                deadline = null
                FrontAction.ReturnFailed(willRetry = false)
            }
        }
        val d = deadline ?: return FrontAction.HideBar
        val left = d - now
        if (left <= 0) {
            attempts = 1
            issuedAt = now
            deadline = null
            barShown = false
            events.add(FrontEvent.Closing(kind, idleMs(), now - lastActivity))
            return FrontAction.ReturnNow(1, if (returnedReason != FrontReason.NONE) returnedReason else reason)
        }
        return if (left <= countdownTotal && (inCountdown || left <= BAR_LAST_MS)) {
            val total = if (inCountdown) RETURN_BAR_MS else BAR_LAST_MS
            if (!barShown) {
                barShown = true
                events.add(FrontEvent.BarStart(kind, total, idleMs()))
            }
            FrontAction.ShowBar(left, total)
        } else {
            FrontAction.HideBar
        }
    }

    /** After [FrontAction.ReturnFailed] with `willRetry`: the glue issues the return again; this names the attempt. */
    val retryAttempt: Int get() = attempts

    /** `overdue` of the heartbeat. */
    fun overdue(): Boolean {
        if (!active || reason != FrontReason.HAND) return false
        val idle = idleMs()
        if (idle <= 0) return false
        return clockMs() - lastActivity > idle + OVERDUE_EXTRA_MS
    }

    companion object {
        /** The bar after a call or a missed ring, and the last seconds of the idle count. */
        const val RETURN_BAR_MS = 10_000L
        const val BAR_LAST_MS = 10_000L

        /** A return is judged this long after it was issued (the app's activity resumed again = it failed). */
        const val RETURN_CHECK_MS = 3_000L

        /** `overdue`: past the idle timeout plus this. */
        const val OVERDUE_EXTRA_MS = 120_000L

        const val DEFAULT_IDLE_MIN = 5
        val IDLE_CHOICES_MIN = listOf(1, 2, 5, 10, 15, 30, 60)
        fun idleMs(minutes: Int): Long = minutes.coerceIn(1, 60) * 60_000L
    }
}

/** The touch guard of a ring window on a wall tablet (design section 6): a dashboard is touched all day, so touches in the first 700 ms are ignored. */
class TouchGuard(private val shownAtMs: Long, private val windowMs: Long = GUARD_MS) {
    fun accepts(nowMs: Long): Boolean = nowMs - shownAtMs >= windowMs

    companion object {
        const val GUARD_MS = 700L
    }
}

/** The last ring this panel judged, for the heartbeat's `last_ring_*` fields (design section 9) and the `wall ring-front` journal line. */
enum class RingPath(val wire: String) { DIRECT("direct"), FSI("fsi"), BANNER("banner"), FAILED("failed"), FILTERED("filtered") }

class PanelRingLog {
    data class Last(val callId: String, val door: String?, val path: RingPath)

    @Volatile
    var last: Last? = null
        private set

    /** The ring [callId] at [door] (a door id, never a host) ended up on [path]. A later path for the same call replaces the earlier (banner after a failed direct). */
    fun record(callId: String, door: String?, path: RingPath) {
        last = Last(callId, door, path)
    }

    /** True for the paths that mean the ring window (or the full-screen intent) was shown, as the server's `ring_sent_not_shown` counts them. */
    fun shown(): Boolean = last?.path.let { it == RingPath.DIRECT || it == RingPath.FSI }
}
