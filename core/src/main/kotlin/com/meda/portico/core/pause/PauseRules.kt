package com.meda.portico.core.pause

import com.meda.portico.core.push.PushMessage
import com.meda.portico.core.push.Source
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What is stored for a pause. [untilMs] is 0 for "until I resume". Wall-clock milliseconds,
 * because the deadline must survive a reboot (`elapsedRealtime` restarts at zero on every boot).
 */
data class PauseState(val paused: Boolean, val sinceMs: Long, val untilMs: Long) {
    override fun toString(): String =
        if (!paused) "not paused" else "paused since $sinceMs " + if (untilMs == 0L) "until resumed" else "until $untilMs"

    companion object {
        val NOT_PAUSED = PauseState(false, 0L, 0L)
    }
}

/** What happens to a push (any kind) given the pause. */
enum class PushAction { RING, DROP }

/**
 * PAUSE, AS PURE LOGIC (the owner, 2026-10-09: "add a pause button to the portico app"): is this phone
 * paused right now, until when, and what happens to a push or a ring while it is, and after.
 *
 * Ported from the old intercom app's `PauseRules.java` (0.52.0-alpha), which got the same switch
 * the same day: with both apps on one phone, either one can be paused to test the other alone.
 * `PauseRulesTest` carries that app's table plus Portico's own rows.
 *
 * THE CLOCK IS THE WALL CLOCK, so it can move backwards (set by hand, a network time correction).
 * A timed pause never runs longer than its own length measured from the moment it began, and a
 * clock that reads EARLIER than that moment cannot be trusted: the pause ends. A stored state
 * that is nonsense (a deadline not after its start, or more than [MAX_TIMED_MS] after it) is
 * expired. Every doubt resolves towards ringing: a doorbell that comes back early is a nuisance
 * during a test, one that stays deaf is a missed visitor.
 */
object PauseRules {
    const val HOUR = 3_600_000L

    /** The longest TIMED pause Setup offers; a stored span longer than this is corrupt. */
    const val MAX_TIMED_MS = 4 * HOUR

    /** Setup's three buttons. 0 means "until I resume". */
    const val ONE_HOUR = HOUR
    const val FOUR_HOURS = 4 * HOUR
    const val UNTIL_RESUMED = 0L

    /** A new pause chosen at [nowMs]. Throws for a length Setup does not offer. */
    fun start(nowMs: Long, lengthMs: Long): PauseState {
        require(lengthMs in 0..MAX_TIMED_MS) { "bad pause length $lengthMs" }
        return PauseState(true, nowMs, if (lengthMs == UNTIL_RESUMED) 0L else nowMs + lengthMs)
    }

    /** Is the pause in force at [nowMs]? */
    fun active(s: PauseState?, nowMs: Long): Boolean = s != null && s.paused && !expired(s, nowMs)

    /**
     * True when a stored pause should end NOW: its deadline has passed, the clock reads earlier
     * than the moment it began, or the stored deadline is nonsense. Never for "until resumed".
     */
    fun expired(s: PauseState?, nowMs: Long): Boolean {
        if (s == null || !s.paused) return false
        if (s.untilMs == 0L) return false
        if (nowMs < s.sinceMs) return true
        if (corrupt(s)) return true
        return nowMs >= s.untilMs
    }

    private fun corrupt(s: PauseState): Boolean = s.untilMs <= s.sinceMs || s.untilMs - s.sinceMs > MAX_TIMED_MS

    /** Milliseconds left, for the alarm: 0 for "until resumed", not paused, or already due. */
    fun remaining(s: PauseState?, nowMs: Long): Long {
        if (s == null || !active(s, nowMs) || s.untilMs == 0L) return 0L
        return (s.untilMs - nowMs).coerceAtLeast(0L)
    }

    /**
     * The moment the pause ended, for the replay rule. A deadline noticed late (the phone was off,
     * the process dead) ended AT the deadline; a person pressing Resume, a clock that cannot be
     * trusted, or a corrupt state ends it NOW.
     */
    fun resumedAt(s: PauseState?, nowMs: Long): Long {
        if (s == null || !s.paused) return nowMs
        if (s.untilMs != 0L && nowMs >= s.sinceMs && !corrupt(s) && nowMs >= s.untilMs) return s.untilMs
        return nowMs
    }

    /** Any push, any kind: dropped while paused. */
    fun onPush(s: PauseState?, nowMs: Long): PushAction = if (active(s, nowMs)) PushAction.DROP else PushAction.RING

    /**
     * UNPAUSING MUST NOT REPLAY MISSED RINGS AS RINGS: a ring whose call time is not newer than
     * [resumedAt] is history. [resumedAt] 0 (never paused) or an unknown call time (<= 0) lets the
     * ring through, as every build before this one did.
     */
    fun replayable(callEpochMs: Long, resumedAt: Long): Boolean {
        if (resumedAt <= 0L || callEpochMs <= 0L) return true
        return callEpochMs > resumedAt
    }

    /**
     * When the call [ring] is about began, or -1 when unknown. A pushed ring carries the server's
     * call time `t`. A socket ring carries none (its `t` is the phone's arrival time), so the call
     * id's own stamp is used: the server's ids are `gso-<epoch nanoseconds>-<n>-<rand>`.
     */
    fun callTimeMs(ring: PushMessage.Ring): Long = when (ring.source) {
        Source.FCM -> ring.tMs
        Source.WS -> callIdEpochMs(ring.callId)
    }

    /** The replay rule applied to one ring. */
    fun withheld(ring: PushMessage.Ring, resumedAt: Long): Boolean = !replayable(callTimeMs(ring), resumedAt)

    /** `gso-<19-digit epoch ns>-...` -> epoch ms; -1 for any other id (unknown, never withheld). */
    fun callIdEpochMs(callId: String?): Long {
        val parts = (callId ?: return -1L).split('-')
        if (parts.size < 3 || parts[0] != "gso") return -1L
        val ns = parts[1]
        if (ns.length != 19 || !ns.all { it in '0'..'9' }) return -1L
        return ns.toLong() / 1_000_000L
    }
}

/** Where the pause lives (the app: a private prefs file written with `commit()`). */
interface PauseStore {
    fun load(): PauseState

    /** Durable when it returns true. */
    fun save(state: PauseState): Boolean

    /** Clears the pause and records when it ended, in one durable write. */
    fun saveResumed(resumedAtMs: Long): Boolean

    /** When the last pause ended (epoch ms), 0 if never. */
    fun loadResumedAt(): Long
}

class InMemoryPauseStore : PauseStore {
    @Volatile var state: PauseState = PauseState.NOT_PAUSED
    @Volatile var resumedAt: Long = 0L
    @Volatile var failWrites = false
    @Volatile var throwOnRead = false
    @Volatile var writes = 0

    override fun load(): PauseState {
        if (throwOnRead) error("unreadable")
        return state
    }

    override fun save(state: PauseState): Boolean {
        writes++
        if (failWrites) return false
        this.state = state
        return true
    }

    override fun saveResumed(resumedAtMs: Long): Boolean {
        writes++
        if (failWrites) return false
        state = PauseState.NOT_PAUSED
        resumedAt = resumedAtMs
        return true
    }

    override fun loadResumedAt(): Long {
        if (throwOnRead) error("unreadable")
        return resumedAt
    }
}

/**
 * The stored pause as every hot path asks it. FAIL OPEN: any exception while reading means NOT
 * paused (a ring is never lost to a corrupt flag). [active] also NOTICES a deadline that has
 * passed (the alarm may have been lost to a reboot, Doze or a dead process) and calls
 * [onExpired] once, so whichever path asks first after the deadline brings the phone back; the
 * caller resumes on its own thread and gets "not paused" as its answer.
 */
class PauseKeeper(
    private val store: PauseStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onExpired: () -> Unit = {},
) {
    private val expirySignalled = AtomicBoolean(false)
    private val lock = Any()

    /** The stored state, or [PauseState.NOT_PAUSED] if it cannot be read. */
    fun state(): PauseState = try {
        store.load()
    } catch (_: Exception) {
        PauseState.NOT_PAUSED
    }

    fun active(): Boolean = try {
        val s = store.load()
        if (!s.paused) {
            false
        } else {
            val now = clock()
            if (PauseRules.expired(s, now)) {
                if (expirySignalled.compareAndSet(false, true)) runCatching(onExpired)
                false
            } else {
                PauseRules.active(s, now)
            }
        }
    } catch (_: Exception) {
        false
    }

    fun onPush(): PushAction = if (active()) PushAction.DROP else PushAction.RING

    /** Starts a pause; null when the write failed (then nothing is paused). Throws for a bad length. */
    fun pause(lengthMs: Long): PauseState? = synchronized(lock) {
        val s = PauseRules.start(clock(), lengthMs)
        if (!store.save(s)) return null
        expirySignalled.set(false)
        s
    }

    /** Ends the pause; returns the recorded resume time, or null if nothing was paused (idempotent). */
    fun resume(): Long? = synchronized(lock) {
        val s = state()
        if (!s.paused) return null
        val at = PauseRules.resumedAt(s, clock())
        store.saveResumed(at)
        expirySignalled.set(false)
        at
    }

    /** When the last pause ended, 0 if never or unreadable. */
    fun resumedAt(): Long = try {
        store.loadResumedAt()
    } catch (_: Exception) {
        0L
    }
}
