package com.meda.portico.core.call

import com.meda.portico.core.ha.HaInbound
import kotlinx.serialization.json.JsonObject

/** What came back for one command: the server's reply, nothing sent, or the socket closed first. */
sealed interface CommandReply {
    class Reply(val result: HaInbound.Result) : CommandReply
    data object NotSent : CommandReply
    data object Lost : CommandReply
}

/** Sends one WebSocket command and calls [onReply] exactly once, on any thread. Never throws. */
fun interface CommandPort {
    fun send(type: String, body: JsonObject, onReply: (CommandReply) -> Unit)
}

/**
 * One WebRTC peer connection with a microphone track, as the answer path needs it. The real one
 * is `WebRtcMedia` in `:app` (libwebrtc); tests use a fake. Every callback may come on any
 * thread; the owner serialises them.
 */
interface MediaCall {
    /** Creates the offer and waits for ICE gathering to finish or [gatherTimeoutMs]; [onSdp] gets the local SDP, null on failure. */
    fun createOffer(gatherTimeoutMs: Long, onSdp: (String?) -> Unit)

    fun setRemoteAnswer(sdp: String, onDone: (Boolean) -> Unit)

    fun setMuted(muted: Boolean)

    /**
     * HOLD (design 15.13): [held] = true turns the microphone AND the speaker off and keeps every leg up (no PeerConnection
     * close, the uplink keeps flowing as silence); false turns the speaker on first, then the microphone. Phone-local, never
     * sent to the server. A resume must not open a microphone the person muted (`setMuted(true)` stays in force). Only
     * [AnswerSession.setHeld] calls it, and only from a press. The default THROWS: a media that cannot hold must say so, and the
     * session then refuses the hold and leaves the call as it was (an implementation that forgot it fails loudly, never silently).
     */
    fun setHeld(held: Boolean) {
        throw UnsupportedOperationException("this media cannot be put on hold")
    }

    /** Stops the microphone and the connection. Idempotent, returns at once (teardown may finish later), never throws. */
    fun close()

    /**
     * DEV-698, observation only: reads the call's audio facts ONCE (one `getStats`, no per-frame work) and gives them to [onStats],
     * on any thread, null if they cannot be read. The default has none. Never throws; never changes the media.
     */
    fun audioStats(onStats: (AudioStats?) -> Unit) {
        onStats(null)
    }

    /**
     * Like [close] (microphone off at once, returns at once) but the media first reads its stats one last time and hands them to
     * [onStats] (at most once, on any thread, within a short bound; not at all on a repeat close). The default closes and reports none.
     */
    fun closeReporting(onStats: (AudioStats?) -> Unit) {
        close()
        onStats(null)
    }

    /** The session tells the media why it is about to close it. A note for the journal; the default ignores it. */
    fun endedBecause(reason: EndReason) {}
}

interface MediaListener {
    /** Audio is flowing (ICE and DTLS up). May repeat after [onDisconnected]. */
    fun onConnected()

    /** Media stopped for now; it may come back. */
    fun onDisconnected()

    /** Media is gone for good. */
    fun onFailed()
}

/** Opens a [MediaCall] with the microphone already attached. Throws if audio cannot start. */
fun interface MediaPort {
    fun open(listener: MediaListener): MediaCall
}

/** `MODE_IN_COMMUNICATION` and speaker routing. [enter] and [leave] are paired by the session; [leave] is idempotent. */
interface AudioRoute {
    fun enter()
    fun leave()
}

fun interface MicPermission {
    fun granted(): Boolean
}

fun interface Cancellable {
    fun cancel()
}

fun interface Scheduler {
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
}

/** A call the phone claimed and has not yet released with the server. Survives a process death. */
data class ActiveCall(val doorId: String, val callId: String, val claimedAtMs: Long)

/**
 * The calls the phone claimed and has not released: a SET (design 15.13 failure d; a phone may hold two door calls). [save]
 * adds a call (or replaces the record of the same call id), [remove] takes one call out, [loadAll] lists them oldest first.
 * A store written by the single-record app reads as a set of one.
 */
interface ActiveCallStore {
    fun loadAll(): List<ActiveCall>
    fun save(call: ActiveCall)
    fun remove(callId: String)

    /** The oldest record, or null. Kept for callers that only ever expect one. */
    fun load(): ActiveCall? = loadAll().firstOrNull()

    /** Removes every record. */
    fun clear() {
        for (c in loadAll()) remove(c.callId)
    }
}

class InMemoryActiveCallStore(initial: ActiveCall? = null) : ActiveCallStore {
    private val calls = linkedMapOf<String, ActiveCall>()

    init {
        if (initial != null) calls[initial.callId] = initial
    }

    /** The oldest record (a test's view of the single-call case); assigning replaces the whole set. */
    var value: ActiveCall?
        get() = calls.values.firstOrNull()
        set(v) {
            calls.clear()
            if (v != null) calls[v.callId] = v
        }

    override fun loadAll(): List<ActiveCall> = calls.values.toList()
    override fun save(call: ActiveCall) {
        calls[call.callId] = call
    }

    override fun remove(callId: String) {
        calls.remove(callId)
    }
}

enum class Phase { IDLE, AWAITING_MIC, PREPARING, CLAIMING, NEGOTIATING, CONNECTING, IN_CALL, ENDED }

enum class EndReason { USER_HANGUP, REMOTE_HANGUP, FAILED, NETWORK_LOST, TIME_CAP, APP_STOPPED, HOLD_TIMEOUT }

data class AnswerSnapshot(
    val phase: Phase,
    val endReason: EndReason? = null,
    val failure: AnswerFailure? = null,
    val muted: Boolean = false,
    /** Scheduler-independent wall clock of the moment audio first connected, for the on-screen timer. */
    val connectedAtMs: Long? = null,
    /**
     * On hold (design 15.13): microphone and speaker off, legs up. Separate from [muted]; resuming restores the mute. A session
     * that ENDS while held keeps this true, so the coordinator ends it quietly (no screen is told). Always false while
     * [HoldConfig.HOLD_ENABLED] is off.
     */
    val held: Boolean = false,
)

/** Hooks the app wires to the ring, the call store and the foreground service. All optional. */
interface AnswerHooks {
    /** Mic is allowed and audio set-up begins: start the foreground service (it must not start before RECORD_AUDIO is granted). */
    fun onMicReady() {}

    /** Immediately before `portico/answer` is sent: record the call as answered in the shared store. */
    fun onClaiming() {}

    /** The session reached ENDED (called once). */
    fun onEnded(snapshot: AnswerSnapshot) {}

    companion object {
        val NONE = object : AnswerHooks {}
    }
}

data class AnswerConfig(
    /** The cap on one answered call, from the moment the server grants it. */
    val callCapMs: Long = 300_000,
    /** How long a command may wait for HA's reply. */
    val stepTimeoutMs: Long = 10_000,
    /** How long ICE gathering may take before the offer goes out with what has been gathered. */
    val gatherTimeoutMs: Long = 4_000,
    /** From the remote answer to audio flowing. */
    val connectTimeoutMs: Long = 15_000,
    /** How long a lost media path or lost WebSocket may recover before the call is ended. */
    val graceMs: Long = 8_000,
    /** With away media on and WebRTC audio not connected this long after the remote answer, the call moves to frames. */
    val fallbackAfterMs: Long = 4_000,
    /** A held call is ended after this long on hold (15.13: default 120 s; the owner's question 2 of 15.18 is open). */
    val holdLimitMs: Long = HoldConfig.HOLD_LIMIT_MS,
)

/** The hold-and-switch switches (design 15.13). */
object HoldConfig {
    /**
     * THE GATE. false: a second ring while on a call is refused with "You are already on a call." exactly as before M7 H1, and
     * nothing here can put a call on hold. H2 (the screens) turns it on.
     */
    const val HOLD_ENABLED = false

    /** Default hold limit, ms. A constant by design: the owner's answer to 15.18 question 2 changes this line only. */
    const val HOLD_LIMIT_MS = 120_000L

    /** Door sessions per phone: one live, one held. */
    const val MAX_DOOR_SESSIONS = 2
}
