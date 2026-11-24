package com.meda.portico.core.call

import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.HaLog.Level

/** What the outgoing-call screen follows. [generation] counts attempts, so the screen asks for the microphone once per attempt. */
data class PlaceUiState(val request: PlaceRequest?, val snapshot: PlaceSnapshot, val generation: Int) {
    /** A call from a button is under way (not idle, not ended). */
    val isLive: Boolean get() = snapshot.phase != PlacePhase.IDLE && snapshot.phase != PlacePhase.ENDED
}

/**
 * The app's one outgoing-call path: at most one [PlaceSession] at a time, started only by [press] (a call
 * button, or the screen's retry, which is a press of the same button), wired to the foreground service.
 * Everything runs on [post] (the app's single call thread), so a session needs no locks; [state] and
 * [addListener] may be read from any thread.
 *
 * A press is refused, with a state the screen shows, when the phone is paused, when another call is live
 * (an outgoing one for a different button, or an answered door call: [otherCallLive]), and is a no-op for
 * the button whose call is already live (a double tap).
 */
class PlaceCoordinator(
    private val newSession: (request: PlaceRequest, hooks: PlaceHooks, onChange: (PlaceSnapshot) -> Unit) -> PlaceSession,
    /** Runs a task on the call thread, in order. */
    private val post: (() -> Unit) -> Unit,
    private val serviceStart: () -> Unit,
    private val serviceStop: () -> Unit,
    private val paused: () -> Boolean,
    /** True while a door call is being answered (the answer path owns the microphone). */
    private val otherCallLive: () -> Boolean = { false },
    private val log: HaLog = HaLog.NONE,
) {
    private var session: PlaceSession? = null
    private var generation = 0

    private companion object {
        /** The action id of an answered house ring: not a button of the document, only the same-press check's key. */
        const val ANSWER_ACTION = "house-answer"
    }

    @Volatile
    private var current: PlaceUiState = PlaceUiState(null, PlaceSnapshot(PlacePhase.IDLE), 0)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(PlaceUiState) -> Unit>()

    fun state(): PlaceUiState = current

    /** Delivers the current state now (on the call thread) and every change after; returns the remover. */
    fun addListener(l: (PlaceUiState) -> Unit): () -> Unit {
        listeners += l
        post {
            try {
                l(current)
            } catch (e: Exception) {
                log.log(Level.ERROR, "place: a listener threw ${e.javaClass.simpleName}")
            }
        }
        return { listeners.remove(l) }
    }

    /** THE press of a call button. The only way a call begins. */
    fun press(request: PlaceRequest) = post {
        val live = current
        if (live.isLive) {
            if (live.request?.actionId == request.actionId && live.request?.answerCallId == request.answerCallId) return@post // the same button twice: the same call (two house rings are two calls)
            return@post reject(request, PlaceErrors.anotherCall())
        }
        if (otherCallLive()) return@post reject(request, PlaceErrors.anotherCall())
        if (paused()) return@post reject(request, PlaceErrors.paused())
        // A hang-up still owed for the previous call goes out now if it can.
        session?.onLinkRestored()
        begin(request)
    }

    /**
     * THE press of Answer on a house ring (M7 3b-B): the person's own press, never an event. The session answers [callId]
     * (`portico/house/answer`) instead of placing a call, and from there is the same call as a placed one: one at a time, the same
     * microphone prompt, service, audio route, Mute and Hang up, and a door ring or a door Answer ends it first.
     */
    fun answerHouse(callId: String, name: String) = press(PlaceRequest(ANSWER_ACTION, name, answerCallId = callId))

    /** The screen's "Try again": a new press of the same button, by the user. */
    fun retry() {
        val r = current.request ?: return
        press(r)
    }

    private fun begin(request: PlaceRequest) {
        log.log(Level.INFO, "place: press action=${request.actionId}")
        val hooks = object : PlaceHooks {
            override fun onMicReady() = serviceStart()
            override fun onEnded(snapshot: PlaceSnapshot) = serviceStop()
        }
        generation++
        val gen = generation
        val s = newSession(request, hooks) { snap -> publish(PlaceUiState(request, snap, gen)) }
        session = s
        publish(PlaceUiState(request, s.snapshot, gen))
        s.begin()
    }

    fun onMicResult(granted: Boolean) = post { session?.onMicResult(granted) }
    fun hangUp() = post { session?.hangUp() }
    fun setMuted(muted: Boolean) = post { session?.setMuted(muted) }
    fun onHouseCall(event: HouseCallEvent) = post { session?.onHouseCall(event) }
    fun onDoorRinging() = post { session?.onDoorRinging() }

    /**
     * The door's Answer was pressed: end any outgoing call NOW, before the answer begins. Must be called on the
     * call thread (the answer press already runs there), so the outgoing call's audio route, service stop and
     * notification are settled before the answer touches any of them.
     */
    fun endForDoorAnswer() {
        session?.onDoorRinging()
    }
    fun onLinkLost() = post { session?.onLinkLost() }
    fun onLinkReady() = post { session?.onLinkRestored() }

    /** The process is going away: release a live call. Safe to call when there is none. */
    fun appStopping() = post { session?.onAppStopping() }

    private fun reject(request: PlaceRequest, failure: PlaceFailure) {
        log.log(Level.INFO, "place: press refused action=${request.actionId} ${failure.kind.name.lowercase()}")
        generation++
        publish(PlaceUiState(request, PlaceSnapshot(PlacePhase.ENDED, EndReason.FAILED, failure), generation))
    }

    private fun publish(s: PlaceUiState) {
        current = s
        for (l in listeners) {
            try {
                l(s)
            } catch (e: Exception) {
                log.log(Level.ERROR, "place: a listener threw ${e.javaClass.simpleName}")
            }
        }
    }
}
