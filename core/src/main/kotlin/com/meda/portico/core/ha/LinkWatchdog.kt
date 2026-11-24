package com.meda.portico.core.ha

/**
 * DEV-764 aftermath: an application-level liveness check for the open Home Assistant socket, as plain decisions (no clock, no thread).
 *
 * Why: a tailnet link (or a Wi-Fi that quietly stopped forwarding) can show "connected" while no byte moves. OkHttp's own ping
 * (every 30 s, then another 30 s to give up) noticed it only after a measured 2.5 minutes; in that time a ring was answered into a
 * dead tunnel. Here ANY inbound text proves the link, and the silence is probed with HA's own `ping` command (`{"id":N,"type":"ping"}`
 * answered `{"id":N,"type":"pong"}`, part of HA's WebSocket API, so no server change):
 *
 * - silent for [pingIdleMs] (or [pingActiveMs] while a ring is up or the screen is on): [Verdict.PING];
 * - a command was sent and nothing at all came back for [cmdStaleMs]: [Verdict.PING] too (the link is not proven alive);
 * - a ping was sent and nothing came back for [deadlineMs] ([cmdDeadlineMs] when a command is waiting): [Verdict.DEAD], and the
 *   caller drops the socket and reconnects at once instead of waiting out a 30 s socket timeout.
 *
 * Link thread only. [reset] on every socket open.
 */
class LinkWatchdog(
    private val pingIdleMs: Long = PING_IDLE_MS,
    private val pingActiveMs: Long = PING_ACTIVE_MS,
    private val deadlineMs: Long = DEADLINE_MS,
    private val cmdStaleMs: Long = CMD_STALE_MS,
    private val cmdDeadlineMs: Long = CMD_DEADLINE_MS,
) {
    enum class Verdict {
        NONE, PING, DEAD,

        /** DEV-811, only when `decide(..., race = true)`: the strict deadline passed. Open a second connection beside this one; the link is not dead yet. Offered once per silence. */
        RACE,
    }

    private var lastRxMs = 0L
    private var pingAtMs = -1L
    private var cmdAtMs = -1L
    private var answerUntilMs = -1L
    private var ringSinceMs = -1L
    private var raceOffered = false

    /**
     * Fast rule (the owner, 2026-11-18): the link is checked every [FAST_PING_MS] and ONE missed ping ([FAST_DEADLINE_MS]) is a dead link,
     *  - from the moment a ring starts ([ringStarted]) until it ends ([ringEnded]) or [RING_CAP_MS] pass (a lost "ended" can not keep it on),
     *  - and for [FAST_WINDOW_MS] after an Answer press ([answerPressed]), which outlives the ring (answering ends it).
     * All of it survives [reset]: the socket that replaces a dead one is still inside the window.
     */
    fun ringStarted(nowMs: Long) {
        if (ringSinceMs < 0) ringSinceMs = nowMs
    }

    fun ringEnded() {
        ringSinceMs = -1L
    }

    fun answerPressed(nowMs: Long) {
        answerUntilMs = nowMs + FAST_WINDOW_MS
    }

    /** `answer`, `ring` or null: why the fast rule is on now (an Answer press wins). */
    fun fastReason(nowMs: Long): String? = when {
        nowMs < answerUntilMs -> "answer"
        ringSinceMs >= 0 && nowMs - ringSinceMs < RING_CAP_MS -> "ring"
        else -> null
    }

    fun isFast(nowMs: Long): Boolean = fastReason(nowMs) != null

    /** True when the link has been silent for at least [PROVE_MS]: a new ring should ping it at once rather than wait for a tick. */
    fun needsProof(nowMs: Long): Boolean = !pingOutstanding && nowMs - lastRxMs >= PROVE_MS

    /** DEV-811: the ring and Answer windows belong to the person's situation, not to a socket: a socket that takes over inherits them. */
    fun copyWindowFrom(other: LinkWatchdog) {
        ringSinceMs = other.ringSinceMs
        answerUntilMs = other.answerUntilMs
    }

    fun reset(nowMs: Long) {
        lastRxMs = nowMs
        pingAtMs = -1L
        cmdAtMs = -1L
        raceOffered = false
    }

    /** Any frame arrived: the link is alive. */
    fun onRx(nowMs: Long) {
        lastRxMs = nowMs
        pingAtMs = -1L
        cmdAtMs = -1L
        raceOffered = false
    }

    /** A command was written to the socket; the first one since the last frame starts the "not proven" clock. */
    fun onCommandSent(nowMs: Long) {
        if (cmdAtMs < 0) cmdAtMs = nowMs
    }

    fun onPingSent(nowMs: Long) {
        if (pingAtMs < 0) pingAtMs = nowMs
    }

    /** How long the outstanding ping has gone unanswered, or -1 when none is out. */
    fun pingAgeMs(nowMs: Long): Long = if (pingAtMs >= 0) nowMs - pingAtMs else -1L

    /** True while a ping is out and unanswered. */
    val pingOutstanding: Boolean get() = pingAtMs >= 0

    fun silentMs(nowMs: Long): Long = nowMs - lastRxMs

    /** True when a command is waiting for ANY sign of life. */
    val commandWaiting: Boolean get() = cmdAtMs >= 0

    /**
     * [race] (DEV-811, mobile or tailnet links only): the strict deadline ([FAST_DEADLINE_MS] in the fast rule, else the normal one) no
     * longer means dead. It yields [Verdict.RACE] once, and the link is declared [Verdict.DEAD] only [MOBILE_FAST_DEAD_MS] after the ping in the
     * fast rule ([MOBILE_IDLE_EXTRA_MS] after the normal deadline otherwise). `race = false` (Wi-Fi, the default) is the strict rule as before.
     * [mayRace] false (a stream is open on the socket, a candidate is not allowed now) keeps the long mobile deadlines but offers no RACE.
     * A ring that starts when the silence is already past the mobile deadline still gets its one RACE before DEAD.
     */
    fun decide(nowMs: Long, active: Boolean, race: Boolean = false, mayRace: Boolean = true): Verdict {
        if (isFast(nowMs)) {
            if (pingAtMs >= 0) {
                val waited = nowMs - pingAtMs
                if (!race) return if (waited >= FAST_DEADLINE_MS) Verdict.DEAD else Verdict.NONE
                if (waited >= MOBILE_FAST_DEAD_MS) return if (mayRace && !raceOffered) offerRace(true) else Verdict.DEAD
                return if (mayRace) offerRace(waited >= FAST_DEADLINE_MS) else Verdict.NONE
            }
            return if (nowMs - lastRxMs >= FAST_PING_MS) Verdict.PING else Verdict.NONE
        }
        if (pingAtMs >= 0) {
            val limit = if (cmdAtMs >= 0) cmdDeadlineMs else deadlineMs
            val waited = nowMs - pingAtMs
            if (!race) return if (waited >= limit) Verdict.DEAD else Verdict.NONE
            if (waited >= limit + MOBILE_IDLE_EXTRA_MS) return if (mayRace && !raceOffered) offerRace(true) else Verdict.DEAD
            return if (mayRace) offerRace(waited >= limit) else Verdict.NONE
        }
        val silent = nowMs - lastRxMs
        if (cmdAtMs >= 0 && nowMs - cmdAtMs >= cmdStaleMs) return Verdict.PING
        return if (silent >= (if (active) pingActiveMs else pingIdleMs)) Verdict.PING else Verdict.NONE
    }

    private fun offerRace(due: Boolean): Verdict {
        if (!due || raceOffered) return Verdict.NONE
        raceOffered = true
        return Verdict.RACE
    }

    companion object {
        const val PING_IDLE_MS = 30_000L
        const val PING_ACTIVE_MS = 10_000L
        const val DEADLINE_MS = 8_000L
        const val CMD_STALE_MS = 3_000L
        const val CMD_DEADLINE_MS = 4_000L

        /** After an Answer press: how long the fast rule lasts, how often it asks, and how long one unanswered ping may take. */
        const val FAST_WINDOW_MS = 15_000L
        const val FAST_PING_MS = 2_000L
        const val FAST_DEADLINE_MS = 2_000L

        /** DEV-811: on a mobile or tailnet link a ping unanswered this long (fast rule) is dead; the race starts at [FAST_DEADLINE_MS]. */
        const val MOBILE_FAST_DEAD_MS = 6_000L

        /** DEV-811: outside the fast rule, how long after the normal deadline a racing link is declared dead. */
        const val MOBILE_IDLE_EXTRA_MS = 4_000L

        /** A ring that never says it ended keeps the fast rule on this long at most. */
        const val RING_CAP_MS = 60_000L

        /** At a ring start, a link whose last frame is older than this is pinged at once. */
        const val PROVE_MS = 1_000L

        /** How often the caller runs [decide] inside the fast window (the normal [TICK_MS] is too coarse for a 2 s deadline). */
        const val FAST_TICK_MS = 500L

        /** How often the caller should run [decide]: it bounds how late a trip can be. */
        const val TICK_MS = 2_000L
    }
}
