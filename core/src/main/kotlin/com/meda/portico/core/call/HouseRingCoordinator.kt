package com.meda.portico.core.call

import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.HaLog.Level
import com.meda.portico.core.push.Contract
import com.meda.portico.core.push.HouseStep
import com.meda.portico.core.push.PushJournal
import com.meda.portico.core.push.Redact

/** A house ring as the screens draw it. [name] is null for a caller the document does not name: the screen says "Someone". Never an id. */
data class HouseRing(val callId: String, val name: String?, val source: Source, val expiresAtMs: Long) {
    enum class Source { SOCKET, PUSH }
}

/** What the ring screen follows. */
data class HouseRingState(val ring: HouseRing?)

/** Draws and withdraws the ring on the phone (the notification and the full-screen screen). Called on the answer thread. */
interface HouseRingUi {
    fun show(ring: HouseRing)

    /** The ring is over for this phone (answered here or elsewhere, declined, ended, expired, outranked). */
    fun dismiss(callId: String, why: HouseRingCoordinator.Dismiss)
}

/**
 * The CALLEE side of a house call before it is answered (M7 3b-B): decides whether a house ring is shown and what ends it. A house
 * ring arrives by the socket (`house_call` `ringing`, the event whose `to` is this phone's own endpoint) or, for an away phone, by
 * the push tail (`ct: house`, [HousePush]); both come here and nowhere else (the door decision never sees one).
 *
 * The rules, in the order they are applied to a ring ([Outcome]):
 * 1. house calls must be ON for this phone (the roster says so: `enabled`) and this phone's own "Receive house calls in Portico"
 *    switch ON (default OFF), and Android's Do Not Disturb not on (HC-4: a house call never rings through it, a door call does); else silent;
 * 2. not paused; 3. not a ring already seen (a snapshot after a reconnect, a push and a socket event for one call, a late ring
 *    after its `ended`); 4. **a door outranks a house call** (design 7, 15.13): a door ringing, or a door call live OR HELD, makes a
 *    house ring silent: it is dropped, not queued, and the phone never switches to it (the server refuses such a call itself, with
 *    `busy`; this is the second layer, for a client that was told late); 5. one call at a time: a house ring while any other call of
 *    this phone is live is silent; 6. a ring whose window has passed is silent.
 * A door ring that comes while a house ring is up withdraws it ([onDoorRinging]); a house call already ANSWERED is ended by the
 * door's Answer ([PlaceCoordinator.endForDoorAnswer]), as an outgoing call is.
 *
 * Nothing here answers: [answer] is the person's press of Answer (button or notification action), the only way an answer begins.
 * Everything runs on [post] (the app's single call thread).
 */
class HouseRingCoordinator(
    private val enabled: () -> Boolean,
    private val receiveOn: () -> Boolean,
    /** Android's own Do Not Disturb is on AND this phone lets Portico read it (the second switch): a house ring never rings through it. */
    private val dndOn: () -> Boolean,
    private val paused: () -> Boolean,
    /** A door is ringing, or a door call is live or held. */
    private val doorBusy: () -> Boolean,
    /** Any other call of this phone is live (placed, talk, or an answered house call). */
    private val callLive: () -> Boolean,
    private val myEndpoint: () -> String?,
    private val profile: () -> ContentProfile,
    private val commands: CommandPort,
    private val answerHouse: (callId: String, name: String) -> Unit,
    private val ui: HouseRingUi,
    private val scheduler: Scheduler,
    private val clockMs: () -> Long,
    private val post: (() -> Unit) -> Unit,
    private val journal: PushJournal? = null,
    private val log: HaLog = HaLog.NONE,
    /**
     * Panel mode (DEV-807, design section 5 item 3 R8): while [myEndpoint] is unknown, house events are held instead of dropped, at most
     * [BUFFER_MAX] of them and each at most [BUFFER_WINDOW_MS], and `answered`/`ended` events are held with the rings so a late `ended`
     * for a ring the buffer rings leaves nothing up. [onEndpointKnown] replays them in order. Off (the default) keeps the phone's old rule:
     * an event with no endpoint to compare against is ignored.
     */
    private val bufferWhileUnknown: () -> Boolean = { false },
) {
    enum class Dismiss { ANSWERED_HERE, ANSWERED_ELSEWHERE, DECLINED, ENDED, EXPIRED, DOOR, LINK_LOST, REPLACED }

    /** Why a ring was not shown. */
    enum class Outcome {
        RING, SILENT_OFF, SILENT_RECEIVE_OFF, SILENT_DND, SILENT_PAUSED, SILENT_STALE, SILENT_DOOR_BUSY, SILENT_BUSY, SILENT_LATE, SILENT_NOT_FOR_ME,
    }

    @Volatile
    private var current: HouseRingState = HouseRingState(null)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(HouseRingState) -> Unit>()
    private var expiry: Cancellable? = null

    /** The highest `seq` seen per call (ring 1, answered 2, ended 3): the door path's own staleness rule, bounded. */
    private val seen = object : LinkedHashMap<String, Int>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > Contract.STORE_CAPACITY
    }

    fun state(): HouseRingState = current

    fun addListener(l: (HouseRingState) -> Unit): () -> Unit {
        listeners += l
        post { l(current) }
        return { listeners.remove(l) }
    }

    // ---- what arrives -----------------------------------------------------------------------------------------------

    /** A `house_call` event from the socket (any, including this phone's own placed call: only a ring for this endpoint rings). */
    fun onHouseCall(e: HouseCallEvent) = post {
        if (myEndpoint() == null && bufferWhileUnknown()) {
            hold(e)
            return@post
        }
        handle(e)
    }

    /** The id this phone is called by became known (or changed): replay what was held, oldest first, dropping what has aged out. */
    fun onEndpointKnown() = post {
        val now = clockMs()
        val held = buffer.filter { now - it.atMs <= BUFFER_WINDOW_MS }.map { it.event }
        buffer.clear()
        if (myEndpoint() == null) return@post
        for (e in held) handle(e)
    }

    private class Held(val event: HouseCallEvent, val atMs: Long)

    private val buffer = ArrayDeque<Held>()

    private fun hold(e: HouseCallEvent) {
        val now = clockMs()
        while (buffer.isNotEmpty() && now - buffer.first().atMs > BUFFER_WINDOW_MS) buffer.removeFirst()
        buffer.addLast(Held(e, now))
        while (buffer.size > BUFFER_MAX) buffer.removeFirst()
        log.log(Level.INFO, "house: held ${e.state} call=${Redact.prefix(e.callId)} until the endpoint is known (${buffer.size})")
    }

    private fun handle(e: HouseCallEvent) {
        when (e.state) {
            "ringing" -> {
                val me = myEndpoint()
                // The phone's own placed call, a screen's group copy, or an event with no addressee: not a ring for this phone.
                if (me == null || e.to != me || e.from == me) return
                offer(e.callId, fromId = e.from, fromRef = null, expMs = null, source = HouseRing.Source.SOCKET, seq = 1)
            }
            "answered", "active", "ended" -> withdraw(e.callId, if (e.state == "ended") ended(e.reason) else Dismiss.ANSWERED_ELSEWHERE, seq = if (e.state == "ended") 3 else 2)
        }
    }

    /** A house push (`ct: house`). */
    fun onPush(msg: HousePush, nowMs: Long = clockMs()) = post {
        when (msg) {
            is HousePush.Ring -> offer(msg.callId, fromId = null, fromRef = msg.fromRef, expMs = msg.expMs, source = HouseRing.Source.PUSH, seq = msg.seq, nowMs = nowMs)
            is HousePush.Answered -> withdraw(msg.callId, Dismiss.ANSWERED_ELSEWHERE, msg.seq)
            is HousePush.Ended -> withdraw(msg.callId, Dismiss.ENDED, msg.seq)
        }
    }

    private fun ended(reason: String?): Dismiss = if (reason == "answered_elsewhere") Dismiss.ANSWERED_ELSEWHERE else Dismiss.ENDED

    private fun offer(callId: String, fromId: String?, fromRef: String?, expMs: Long?, source: HouseRing.Source, seq: Int, nowMs: Long = clockMs()) {
        val outcome = decide(callId, expMs, seq, nowMs)
        journal?.house(HouseStep.RING, callId, outcome)
        log.log(Level.INFO, "house: ring call=${Redact.prefix(callId)} via=${source.name.lowercase()} -> ${outcome.name.lowercase()}")
        if (outcome != Outcome.RING) return
        val p = profile()
        val id = fromId ?: fromRef?.let { HouseCallers.endpointForRef(p, it) }
        val ring = HouseRing(callId, HouseCallers.nameFor(p, id), source, expiresAtMs = (expMs ?: (nowMs + RING_WINDOW_MS)) + Contract.SKEW_MS)
        current.ring?.let { old -> dismiss(old, Dismiss.REPLACED) } // a different call id replaces it (design 7)
        current = HouseRingState(ring)
        arm(ring, nowMs)
        publish()
        ui.show(ring)
    }

    private fun decide(callId: String, expMs: Long?, seq: Int, nowMs: Long): Outcome {
        if (!enabled()) return Outcome.SILENT_OFF
        if (!receiveOn()) return Outcome.SILENT_RECEIVE_OFF
        if (dndOn()) return Outcome.SILENT_DND
        if (paused()) return Outcome.SILENT_PAUSED
        val previous = seen[callId]
        if (previous != null && seq <= previous) return Outcome.SILENT_STALE
        seen[callId] = seq
        if (doorBusy()) return Outcome.SILENT_DOOR_BUSY
        if (callLive()) return Outcome.SILENT_BUSY
        if (expMs != null && nowMs > expMs + Contract.SKEW_MS) return Outcome.SILENT_LATE
        return Outcome.RING
    }

    private fun withdraw(callId: String, why: Dismiss, seq: Int) {
        val previous = seen[callId]
        if (previous == null || seq > previous) seen[callId] = seq
        val ring = current.ring ?: return
        if (ring.callId != callId) return
        dismiss(ring, why)
    }

    // ---- what the person and the rest of the app do ------------------------------------------------------------------

    /** THE press of Answer. The only way an answer begins; nothing else here ever answers. */
    fun answer(callId: String) = post {
        val ring = current.ring?.takeIf { it.callId == callId } ?: return@post
        // A door that rang or was answered while the screen was up outranks: the ring goes, the call is not answered.
        if (doorBusy() || paused()) {
            dismiss(ring, Dismiss.DOOR)
            return@post
        }
        journal?.house(HouseStep.ANSWER_PRESSED, callId, null)
        dismiss(ring, Dismiss.ANSWERED_HERE)
        answerHouse(callId, ring.name ?: SOMEONE)
    }

    /** The press of Decline: told to the server (local on a group call, terminal on a 1:1), withdrawn here. */
    fun decline(callId: String) = post {
        val ring = current.ring?.takeIf { it.callId == callId } ?: return@post
        journal?.house(HouseStep.DECLINE_PRESSED, callId, null)
        dismiss(ring, Dismiss.DECLINED)
        commands.send(HouseCommands.DECLINE, HouseCommands.decline(callId)) { /* any reply settles it: the ring is already gone here */ }
    }

    /** A door started ringing: it outranks a house ring, which is withdrawn (the server cancels it for this phone too). */
    fun onDoorRinging() = post { current.ring?.let { dismiss(it, Dismiss.DOOR) } }

    /** The socket closed: the server drops a ringing call with it, and a ring that came by the socket is gone. A pushed ring stays until its window ends. */
    fun onLinkLost() = post {
        buffer.clear() // held events belong to the closed socket
        current.ring?.takeIf { it.source == HouseRing.Source.SOCKET }?.let { dismiss(it, Dismiss.LINK_LOST) }
    }

    /** House calls were switched off for this phone (the roster no longer says on) or its own switch went off: nothing may keep ringing. */
    fun onSwitchedOff() = post { current.ring?.let { dismiss(it, Dismiss.ENDED) } }

    private fun arm(ring: HouseRing, nowMs: Long) {
        expiry?.cancel()
        expiry = scheduler.schedule((ring.expiresAtMs - nowMs).coerceAtLeast(0)) {
            if (current.ring?.callId == ring.callId) dismiss(ring, Dismiss.EXPIRED)
        }
    }

    private fun dismiss(ring: HouseRing, why: Dismiss) {
        if (current.ring?.callId != ring.callId) return
        expiry?.cancel()
        expiry = null
        current = HouseRingState(null)
        journal?.house(HouseStep.DISMISSED, ring.callId, why)
        log.log(Level.INFO, "house: ring over call=${Redact.prefix(ring.callId)} why=${why.name.lowercase()}")
        publish()
        try {
            ui.dismiss(ring.callId, why)
        } catch (e: Exception) {
            log.log(Level.ERROR, "house: the ring ui threw ${e.javaClass.simpleName}")
        }
    }

    private fun publish() {
        val s = current
        for (l in listeners) {
            try {
                l(s)
            } catch (e: Exception) {
                log.log(Level.ERROR, "house: a listener threw ${e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        /** Panel mode: house events held while the phone endpoint is unknown (design section 5 item 3 R8). */
        const val BUFFER_MAX = 8
        const val BUFFER_WINDOW_MS = 10_000L

        /** The server's ring window; a ring seen on the socket carries no expiry. */
        const val RING_WINDOW_MS = Contract.DEFAULT_RING_WINDOW_S * 1000L

        /** The name the screens use for a caller the document does not name. */
        const val SOMEONE = "Someone"
    }
}
