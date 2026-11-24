package com.meda.portico.core.panel

import com.meda.portico.core.call.Cancellable
import com.meda.portico.core.call.PlacePhase
import com.meda.portico.core.call.PlaceRequest
import com.meda.portico.core.call.PlaceUiState
import com.meda.portico.core.call.Scheduler
import com.meda.portico.core.ha.RosterEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/*
 * D-20261121-c98a: the kitchen tablet's "Call phones" dashboard button. The server (portico-ha `portico.call_everyone`) sends this
 * panel's app one event on its calls stream, `{type: "panel_call", request_id, target: "everyone"}`; the app comes to the front, places
 * the Everyone house call as an ordinary press of the Everyone row, and answers `portico/panel/call_result`. See
 * automation/portico_panel_call_phones_design.md in the estate repo.
 */

/** The event and the answer on the wire, and the refusal codes the server knows by name. */
object PanelCallWire {
    const val EVENT = "panel_call"
    const val RESULT = "portico/panel/call_result"
    const val TARGET_EVERYONE = "everyone"

    const val NOT_PANEL_MODE = "not_panel_mode"
    const val BUSY = "busy"
    const val PAUSED = "paused"
    const val NO_EVERYONE = "no_everyone"
    const val NO_FRONT = "no_front"
    const val TIMEOUT = "timeout"
    const val REFUSED = "refused"

    private val ID = Regex("[0-9a-f]{16}")

    /** The request, or null for anything that is not exactly `panel_call` for Everyone with a well-formed id (an unknown target is ignored, never guessed). */
    fun parse(event: JsonObject): PanelCallRequest? {
        if ((event["type"] as? JsonPrimitive)?.contentOrNull != EVENT) return null
        if ((event["target"] as? JsonPrimitive)?.contentOrNull != TARGET_EVERYONE) return null
        val id = (event["request_id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        return if (ID.matches(id)) PanelCallRequest(id) else null
    }

    /** The body of `portico/panel/call_result`; the link adds `type` and `id`. [text] is bounded to the server's 120 characters. */
    fun result(requestId: String, ok: Boolean, code: String? = null, text: String? = null): JsonObject = buildJsonObject {
        require(ID.matches(requestId)) { "request_id" }
        put("request_id", requestId)
        put("ok", ok)
        if (code != null) put("code", code.take(40))
        if (text != null) put("text", text.take(120))
    }
}

@JvmInline
value class PanelCallRequest(val requestId: String)

/** Where a parsed request goes. Called on the link's single thread. */
fun interface PanelCallSink {
    fun onPanelCall(request: PanelCallRequest)

    companion object {
        val NONE = PanelCallSink { }
    }
}

/** What the coordinator needs to know about the app now. Read at the moment of a request, never cached. */
data class PanelCallGate(
    val panelMode: Boolean,
    /** Any call or ring is live in the app (a placed call, an answered one, Talk, a house ring up). */
    val busy: Boolean,
    val paused: Boolean,
)

/** Where the Everyone call is in the coordinator's handling of one request. */
enum class PanelCallPhase { FRONTING, PLACING }

/**
 * One request at a time, from the server's event to the server's answer. Everything runs on one thread (the answer path's: [post] is the
 * caller's own), so it needs no locks. The order is the safety property:
 *
 *  1. every refusal the app can decide itself (Panel mode, a call or ring live, paused, no usable Everyone row) is answered
 *     BEFORE anything is shown or placed;
 *  2. the call screen is brought to the front, and only when it is up is the call placed ([press]): the phones never ring for a tablet
 *     that shows nothing;
 *  3. the call is a normal press of the Everyone row, so the microphone prompt, the "Microphone is open" banner, one-call-at-a-time and
 *     hang-up are the existing ones, and the microphone opens only after someone answers (PlaceSession).
 *
 * The answer to the server: `ok` when the session reaches RINGING (the phones are ringing and the screen is up), or a refusal with the
 * code and the text the screen shows. A timeout is its own refusal ([FRONT_MS] for the front, [PLACE_MS] for the placing).
 */
class PanelCallCoordinator(
    private val gate: () -> PanelCallGate,
    private val everyone: () -> RosterEntry?,
    /** Brings the call screen to the front and calls [onUp] once it is, or [onFailed] when it cannot. Never throws. */
    private val front: (onUp: () -> Unit, onFailed: (String) -> Unit) -> Unit,
    /** The press of the Everyone row (the only way a call begins). */
    private val press: (PlaceRequest) -> Unit,
    /** Answers the server. Sent once per request. */
    private val reply: (requestId: String, ok: Boolean, code: String?, text: String?) -> Unit,
    private val scheduler: Scheduler,
    /** One journal line for the path (`wall call-request ...`); the caller adds the settings snapshot. */
    private val note: (event: String, requestId: String, code: String?) -> Unit = { _, _, _ -> },
) {
    private class Active(val requestId: String, val action: RosterEntry, var phase: PanelCallPhase) {
        var timer: Cancellable? = null
    }

    private var active: Active? = null
    private val seen = ArrayDeque<String>()

    /** True while a request is being handled (the heartbeat does not need it; tests do). */
    val inFlight: Boolean get() = active != null

    fun onRequest(request: PanelCallRequest) {
        val id = request.requestId
        if (id in seen) return // a repeated event is the same request: one answer only
        seen.addLast(id)
        while (seen.size > SEEN_MAX) seen.removeFirst()
        if (active != null) return refuse(id, PanelCallWire.BUSY, Texts.BUSY)
        val g = gate()
        if (!g.panelMode) return refuse(id, PanelCallWire.NOT_PANEL_MODE, Texts.NOT_PANEL_MODE)
        if (g.paused) return refuse(id, PanelCallWire.PAUSED, Texts.PAUSED)
        if (g.busy) return refuse(id, PanelCallWire.BUSY, Texts.BUSY)
        val row = everyone()
        if (row == null) return refuse(id, PanelCallWire.NO_EVERYONE, Texts.NO_EVERYONE)
        if (!row.available) return refuse(id, PanelCallWire.NO_EVERYONE, Texts.unavailable(row.reason))
        val a = Active(id, row, PanelCallPhase.FRONTING)
        active = a
        note("accepted", id, null)
        a.timer = scheduler.schedule(FRONT_MS) { if (active === a && a.phase == PanelCallPhase.FRONTING) finish(a, PanelCallWire.NO_FRONT, Texts.NO_FRONT) }
        try {
            front({ onFrontUp(a) }, { why -> if (active === a) finish(a, PanelCallWire.NO_FRONT, Texts.noFront(why)) })
        } catch (e: Exception) {
            finish(a, PanelCallWire.NO_FRONT, Texts.NO_FRONT)
        }
    }

    private fun onFrontUp(a: Active) {
        if (active !== a || a.phase != PanelCallPhase.FRONTING) return
        // The screen is up. A call or ring that began while we were fronting wins: nothing is placed over it.
        if (gate().busy) return finish(a, PanelCallWire.BUSY, Texts.BUSY)
        a.phase = PanelCallPhase.PLACING
        a.timer?.cancel()
        a.timer = scheduler.schedule(PLACE_MS) { if (active === a) finish(a, PanelCallWire.TIMEOUT, Texts.TIMEOUT) }
        note("placing", a.requestId, null)
        press(PlaceRequest(a.action.actionId, Texts.name(a.action.name)))
    }

    /** Every change of the app's outgoing-call state (the call thread). Only the state after our press matters. */
    fun onPlaceState(s: PlaceUiState) {
        val a = active ?: return
        if (a.phase != PanelCallPhase.PLACING) return
        if (s.request?.actionId != a.action.actionId) return
        when {
            s.snapshot.phase == PlacePhase.RINGING || s.snapshot.phase == PlacePhase.CONNECTING || s.snapshot.phase == PlacePhase.IN_CALL ->
                finish(a, null, null)
            s.isLive -> Unit // asking for the microphone, or the server is placing: wait for the next state
            s.snapshot.phase == PlacePhase.ENDED -> {
                val failure = s.snapshot.failure
                // A refusal the server or the session gave: the screen shows the same text. A cancel by the user is not an error of the panel.
                finish(a, PanelCallWire.REFUSED, failure?.message ?: Texts.CANCELLED)
            }
        }
    }

    private fun refuse(id: String, code: String, text: String) {
        note("refused", id, code)
        reply(id, false, code, text)
    }

    private fun finish(a: Active, code: String?, text: String?) {
        if (active !== a) return
        active = null
        a.timer?.cancel()
        a.timer = null
        if (code == null) {
            note("ringing", a.requestId, null)
            reply(a.requestId, true, null, null)
        } else {
            note("failed", a.requestId, code)
            reply(a.requestId, false, code, text)
        }
    }

    /** The process is going away or Panel mode was switched off: a waiting request is answered, never left hanging. */
    fun cancelAll() {
        val a = active ?: return
        finish(a, PanelCallWire.NOT_PANEL_MODE, Texts.NOT_PANEL_MODE)
    }

    /** The screen's words. Plain, no ids, no server text beyond what [com.meda.portico.core.call.PlaceFailure] already cleaned. */
    object Texts {
        const val NOT_PANEL_MODE = "Panel mode is off in the Portico app."
        const val PAUSED = "Portico is paused on this tablet."
        const val BUSY = "The tablet is already on a call or a ring."
        const val NO_EVERYONE = "The tablet has no Everyone button (its screen is not set up)."
        const val NO_FRONT = "The Portico app could not come to the front of the screen."
        const val TIMEOUT = "The call did not start in time."
        const val CANCELLED = "The call was cancelled."

        fun noFront(why: String) = "The Portico app could not come to the front ($why)."

        fun unavailable(reason: String?): String = when (reason) {
            "no_receivers" -> "No phone is set to receive house calls right now."
            "busy" -> BUSY
            else -> "Everyone is not available right now."
        }

        fun name(n: String?) = n?.takeIf { it.isNotBlank() } ?: "Everyone"
    }

    companion object {
        /** The call screen has this long to come to the front. Android 15 can block a background start without throwing. */
        const val FRONT_MS = 4_000L

        /** From the press to RINGING: the server's step timer is 10 s, the microphone prompt is not needed (it is checked first). */
        const val PLACE_MS = 14_000L
        private const val SEEN_MAX = 16
    }
}

/** The ringback tone a phone plays while its call rings at the far end. Pure: the Android side only plays what this says. */
object RingbackPolicy {
    /**
     * True while a PLACED call rings at the far end in Panel mode. Not while placing (nobody rings yet), not for an answered house ring
     * (`answering`: the call is ours already), not once the far end picks up (the audio path takes over), and not outside Panel mode:
     * phones keep the behaviour they have today.
     */
    fun wanted(panelMode: Boolean, state: PlaceUiState): Boolean =
        panelMode && state.snapshot.phase == PlacePhase.RINGING && !state.snapshot.answering

    /** The longest a ringback may play: the session's own ring limit (60 s) plus a margin. A backstop that does not depend on the session. */
    const val MAX_MS = 65_000L
}
