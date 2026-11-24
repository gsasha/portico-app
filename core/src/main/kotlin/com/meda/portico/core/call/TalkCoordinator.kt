package com.meda.portico.core.call

import com.meda.portico.core.ha.CallEvent
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.HaLog.Level
import com.meda.portico.core.ha.TalkStateEvent

/** What Home follows for Talk. [generation] counts attempts, so the screen asks for the microphone once per attempt. */
data class TalkUiState(val request: TalkRequest?, val snapshot: TalkSnapshot, val generation: Int) {
    /** A talk is under way (not idle, not ended). */
    val isLive: Boolean get() = snapshot.phase != TalkPhase.IDLE && snapshot.phase != TalkPhase.ENDED
}

/**
 * The app's one talk path: at most one [TalkSession] at a time, started only by [press] (a press of a READY Talk button),
 * wired to the foreground service. The sibling of [PlaceCoordinator]; everything runs on [post] (the app's single call
 * thread), so a session needs no locks.
 *
 * **INERT.** [press] does nothing at all unless [gate] says the button is ready: no state change, no command, no
 * session. [gate] reads the document live (the server's `available`), the same function Home draws the button from, and
 * the session asks it again before it sends. A press is refused, with a line on the button, when the phone is paused or
 * another call is live ([otherCallLive], an answered door call or an outgoing call button); it is a no-op for a second
 * press of the button whose talk is live (Stop is [hangUp], a different gesture).
 *
 * The line saying why a talk ended stays for [TalkConfig.endedShownMs], then the state goes back to idle (the button
 * follows the server again).
 */
class TalkCoordinator(
    private val newSession: (request: TalkRequest, hooks: TalkHooks, gate: () -> Boolean, onChange: (TalkSnapshot) -> Unit) -> TalkSession,
    /** Runs a task on the call thread, in order. */
    private val post: (() -> Unit) -> Unit,
    private val serviceStart: () -> Unit,
    private val serviceStop: () -> Unit,
    private val paused: () -> Boolean,
    /** True while a door call is being answered or an outgoing call button's call is live. */
    private val otherCallLive: () -> Boolean = { false },
    /** Is this Talk button ready, in the document as it is now? The inert switch: see the class comment. */
    private val gate: (TalkRequest) -> Boolean,
    private val later: Scheduler,
    private val config: TalkConfig = TalkConfig(),
    private val log: HaLog = HaLog.NONE,
    /**
     * Called when a talk is over: when it ends (the line shows) and again when the line goes (the state is idle). The app asks Home Assistant for
     * the document again, because the Talk button's `available` / `reason` in it was read while the call was going (`in_call`) and nothing else
     * refreshes it afterwards: without this Home kept saying "a call from this phone is still going" on a door that was idle.
     */
    private val onTalkOver: () -> Unit = {},
    /** DEV-764: write-only observation of every press that did nothing, and why. */
    private val journal: com.meda.portico.core.push.PushJournal? = null,
    /** DEV-764: the document's and the link's word on this button right now ([com.meda.portico.core.content.TalkGate.why]); null when unreadable. */
    private val gateWhy: (TalkRequest) -> com.meda.portico.core.content.TalkGate.Why? = { null },
) {
    private fun jr(event: String, fields: List<Pair<String, Any?>>) {
        try {
            journal?.diag("ui", event, fields)
        } catch (_: Throwable) {
        }
    }

    private var session: TalkSession? = null
    private var generation = 0

    @Volatile
    private var current: TalkUiState = TalkUiState(null, TalkSnapshot(TalkPhase.IDLE), 0)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(TalkUiState) -> Unit>()

    fun state(): TalkUiState = current

    /** Delivers the current state now (on the call thread) and every change after; returns the remover. */
    fun addListener(l: (TalkUiState) -> Unit): () -> Unit {
        listeners += l
        post {
            try {
                l(current)
            } catch (e: Exception) {
                log.log(Level.ERROR, "talk: a listener threw ${e.javaClass.simpleName}")
            }
        }
        return { listeners.remove(l) }
    }

    /** THE press of a Talk button. The only way a talk begins. */
    fun press(request: TalkRequest) = post {
        // The inert switch. Nothing below runs, and nothing is published, for a button the document does not call ready.
        if (!safeGate(request)) {
            log.log(Level.INFO, "talk: press ignored, not ready action=${request.actionId}")
            val w = try { gateWhy(request) } catch (_: Throwable) { null }
            jr("talk-ignored", listOf("why" to w?.word, "avail" to w?.available, "reason" to w?.reason, "usable" to w?.usable, "live" to current.isLive))
            return@post
        }
        val live = current
        if (live.isLive) {
            if (live.request?.actionId == request.actionId) {
                jr("talk-ignored", listOf("why" to "same-button", "phase" to live.snapshot.phase))
                return@post // the same button twice: the same talk
            }
            return@post reject(request, TalkErrors.anotherCall())
        }
        if (otherCallLive()) return@post reject(request, TalkErrors.anotherCall())
        if (paused()) return@post reject(request, TalkErrors.paused())
        // A hang-up still owed for the previous talk goes out now if it can.
        session?.onLinkRestored()
        begin(request)
    }

    private fun begin(request: TalkRequest) {
        val hooks = object : TalkHooks {
            override fun onMicReady() = serviceStart()
            override fun onEnded(snapshot: TalkSnapshot) = serviceStop()
        }
        generation++
        val gen = generation
        val s = newSession(request, hooks, { safeGate(request) }) { snap -> publish(TalkUiState(request, snap, gen)) }
        session = s
        publish(TalkUiState(request, s.snapshot, gen))
        s.begin()
    }

    private fun safeGate(request: TalkRequest): Boolean = try {
        gate(request)
    } catch (e: Exception) {
        false // an unreadable document never means "ready"
    }

    fun onMicResult(granted: Boolean) = post { session?.onMicResult(granted) }

    /** Cancel (connecting) or Stop (in the call), by the user. */
    fun hangUp() = post { session?.hangUp() }
    fun onCallEvent(event: CallEvent) = post { session?.onCallEvent(event) }
    fun onDoorRinging() = post { session?.onDoorRinging() }

    /** A `talk_state` event (P3-C). Inert: with no live session it reaches nothing. */
    fun onTalkState(event: TalkStateEvent) = post { session?.onTalkState(event) }

    /** The door's Answer was pressed: end any talk NOW, on the call thread, before the answer begins. */
    fun endForDoorAnswer() {
        session?.onDoorRinging()
    }

    fun onLinkLost() = post { session?.onLinkLost() }
    fun onLinkReady() = post { session?.onLinkRestored() }

    /** The process is going away: release a live talk. Safe to call when there is none. */
    fun appStopping() = post { session?.onAppStopping() }

    private fun reject(request: TalkRequest, failure: TalkFailure) {
        jr("talk-refuse", listOf("kind" to failure.kind))
        log.log(Level.INFO, "talk: press refused action=${request.actionId} ${failure.kind.name.lowercase()}")
        generation++
        publish(TalkUiState(request, TalkSnapshot(TalkPhase.ENDED, EndReason.FAILED, failure), generation))
    }

    private fun talkOver() {
        try {
            onTalkOver()
        } catch (e: Exception) {
            log.log(Level.ERROR, "talk: the refresh after a talk threw ${e.javaClass.simpleName}")
        }
    }

    private fun publish(s: TalkUiState) {
        current = s
        if (s.snapshot.phase == TalkPhase.ENDED) {
            val gen = s.generation
            talkOver()
            later.schedule(config.endedShownMs) {
                post {
                    val now = current
                    if (now.generation == gen && now.snapshot.phase == TalkPhase.ENDED) {
                        talkOver() // the hang-up has long reached the server: the document now says idle
                        publish(TalkUiState(null, TalkSnapshot(TalkPhase.IDLE), gen))
                    }
                }
            }
        }
        for (l in listeners) {
            try {
                l(s)
            } catch (e: Exception) {
                log.log(Level.ERROR, "talk: a listener threw ${e.javaClass.simpleName}")
            }
        }
    }
}
