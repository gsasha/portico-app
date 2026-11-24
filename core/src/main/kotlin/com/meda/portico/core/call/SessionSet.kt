package com.meda.portico.core.call

import com.meda.portico.core.push.CallKey

/**
 * The door sessions of one phone and the rules for adding to them (design 15.13): at most [HoldConfig.MAX_DOOR_SESSIONS]
 * (two) that have not ended; at most one is LIVE (not held); at most one is HELD. No I/O and no threads: the
 * [AnswerCoordinator] owns one and drives it on the answer thread.
 *
 * A slot's life: created at a press (phase IDLE, then the session's own phases), LIVE while it is not held, HELD between a
 * [AnswerSession.setHeld] `true` and its resume, ENDED for good. An ended slot stays in the set (a hang-up owed to the server
 * is retried through it) until the next press drops it; it never counts against the limit of two.
 */
class SessionSet {

    /** One door session as the coordinator sees it. [snapshot] is the session's latest, kept by its `onChange`. */
    class Slot(val key: CallKey, val doorId: String?, val generation: Int) {
        lateinit var session: AnswerSession
        @Volatile
        var snapshot: AnswerSnapshot = AnswerSnapshot(Phase.IDLE)

        /** Wall-clock ms of the moment this session was put on hold (null when it is not held), for the switcher's hold timer. */
        @Volatile
        var heldAtMs: Long? = null

        val callId: String get() = key.callId
        val ended: Boolean get() = snapshot.phase == Phase.ENDED
        val held: Boolean get() = !ended && snapshot.held
        val connected: Boolean get() = snapshot.phase == Phase.IN_CALL
    }

    /** What a press for a new ring means right now. */
    enum class Decision {
        /** Nothing stands in the way (no session, or only a held one): begin. */
        BEGIN,

        /** The very call that is already live or held: a re-delivered press, a no-op. */
        SAME_CALL,

        /** One live, connected call: the new ring may be answered if the live call is first put on hold. */
        HOLD_AND_BEGIN,

        /** Hold is off ([HoldConfig.HOLD_ENABLED]): the pre-M7 refusal, "You are already on a call." */
        REFUSE_ANOTHER_CALL,

        /** The live call is still connecting: "Wait until the first call is connected." */
        REFUSE_FIRST_NOT_CONNECTED,

        /** Two sessions already (one live, one held): a third ring gets no Answer. */
        REFUSE_BOTH_IN_USE,
    }

    private val slots = java.util.concurrent.CopyOnWriteArrayList<Slot>()

    val all: List<Slot> get() = slots.toList()

    /** Sessions that have not ended. */
    fun active(): List<Slot> = slots.filter { !it.ended }

    /** The one session that is not held and has not ended, or null. */
    fun live(): Slot? = slots.firstOrNull { !it.ended && !it.snapshot.held }

    /** The held sessions (normally at most one; two only after a failed resume, see [AnswerCoordinator.resume]). */
    fun heldSlots(): List<Slot> = slots.filter { it.held }

    fun find(callId: String): Slot? = slots.lastOrNull { it.callId == callId }

    fun forDoor(doorId: String): List<Slot> = slots.filter { it.doorId == doorId }

    fun add(slot: Slot) {
        slots += slot
    }

    /** Drops every ended slot, returns them (the caller has already had them retry what they owe). */
    fun dropEnded(): List<Slot> {
        val gone = slots.filter { it.ended }
        slots.removeAll(gone)
        return gone
    }

    /**
     * Whether, and how, the press for [key] could begin right now. Reads only. [holdEnabled] false reproduces the pre-M7
     * rule exactly: any session that has not ended refuses every other call with `another_call`.
     */
    fun decide(key: CallKey, holdEnabled: Boolean): Decision {
        val active = active()
        if (active.isEmpty()) return Decision.BEGIN
        if (active.any { it.key == key }) return Decision.SAME_CALL
        if (!holdEnabled) return Decision.REFUSE_ANOTHER_CALL
        if (active.size >= HoldConfig.MAX_DOOR_SESSIONS) return Decision.REFUSE_BOTH_IN_USE
        val live = live() ?: return Decision.BEGIN
        return if (live.connected) Decision.HOLD_AND_BEGIN else Decision.REFUSE_FIRST_NOT_CONNECTED
    }
}

/** What the ring screen may offer for a ring (15.13, "Ring screen while on a call"). */
enum class RingOffer {
    /** The plain Answer button, as today. */
    ANSWER,

    /** "Hold <first door> and answer" and Decline. */
    HOLD_AND_ANSWER,

    /** Disabled, with the reason: the first call is not connected yet. */
    WAIT_FOR_FIRST,

    /** No Answer at all (a third ring: "Both of your calls are in use"), and nothing else stands in the way to show. */
    BOTH_IN_USE,

    /** No Answer, for any other reason (paused, unknown door, answered elsewhere, the pre-M7 refusal, already this call). */
    NONE,
}

/** What a [AnswerCoordinator.resume] press did. */
enum class SwitchResult {
    /** The live call is on hold and the asked one is live. */
    SWITCHED,

    /** There was no live call (it ended): the held one is live again. */
    RESUMED,

    /** The asked call is not held (it ended, or it is the live one): nothing changed. */
    NOT_HELD,

    /** Hold is off. */
    DISABLED,

    /** The live call is still connecting: it cannot be held yet. */
    LIVE_NOT_CONNECTED,

    /** The live call could not be put on hold; nothing changed. */
    HOLD_FAILED,

    /** The asked call could not be resumed; the live call was put back as it was. */
    RESUME_FAILED,
}

/** One session as the switcher shows it. */
data class SessionView(val key: CallKey, val snapshot: AnswerSnapshot, val generation: Int, val heldAtMs: Long? = null) {
    val held: Boolean get() = snapshot.held && snapshot.phase != Phase.ENDED
    val ended: Boolean get() = snapshot.phase == Phase.ENDED
}

/**
 * Every door session of the phone, for H2's screens: [live] (not held, not ended), [held], and [ended] (kept until the next
 * press, so the screen can say "Gate call ended"). [seq] grows with every change. [quietEnds] counts the sessions that ENDED
 * while held (they end quietly, 15.13 b): the screen shows one line and no sound. [audioInterrupted]: see its doc.
 */
data class SessionsState(
    val sessions: List<SessionView>,
    val seq: Int,
    val quietEnds: Int = 0,
    /** Another app took the audio focus (a phone call, a player): the live session is silenced by the app until it comes back (H2). */
    val audioInterrupted: Boolean = false,
) {
    val live: SessionView? get() = sessions.firstOrNull { !it.held && !it.ended }
    val held: SessionView? get() = sessions.firstOrNull { it.held }
    val activeCount: Int get() = sessions.count { !it.ended }
    val lastQuietEnd: SessionView? get() = sessions.lastOrNull { it.ended && it.snapshot.held }
}

/**
 * Puts several [AudioRoute] users on one real route (design 15.13 step 6): the first [enter] enters the real route, the last
 * [leave] leaves it, an extra [leave] with nobody in is ignored. `AndroidAudioRoute` keeps its state per instance, and the
 * app builds one per session, so two instances would save `MODE_IN_COMMUNICATION` as the "original" mode and the first call to
 * end would put the other call's mode back to normal. One shared route in front of the real one fixes that.
 */
class RefCountedAudioRoute(private val real: AudioRoute) : AudioRoute {
    private var users = 0

    @Synchronized
    override fun enter() {
        if (users == 0) real.enter()
        users++
    }

    @Synchronized
    override fun leave() {
        if (users == 0) return
        users--
        if (users == 0) real.leave()
    }
}
