package com.meda.portico.core.panel

import com.meda.portico.core.ha.HaInbound
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * What the panel reports about itself (design section 9). Every field is a bounded value; nothing here is a secret or free text.
 * `battery_temp` and `thermal` are dropped on purpose (the tablets report a placeholder battery), and nothing depends on a
 * foreground-app sensor: [foreground] is the app's own lifecycle.
 *
 * [wakeDoors] is null while the app does not know its screen's `wake` list: the body then says `wake_all: true` (section 9: "or
 * `wake_all: true` when `wake` is absent"). The ring facts ([lastRingCall], [lastRingPath], [lastRingDoor]) are left out until the
 * app has one to report. [phoneEndpoint] is the id the app uses for house calls; the server compares it and never stores it.
 */
data class PanelFacts(
    val appVersion: String,
    val panelMode: Boolean,
    val endpointOk: Boolean,
    val foreground: Boolean,
    val frontReason: FrontReason,
    val overdue: Boolean,
    val overlayOk: Boolean,
    val fsiOk: Boolean,
    val microphoneOk: Boolean,
    val notificationsOk: Boolean,
    val ringAudible: Boolean,
    val batteryExempt: Boolean,
    val stayConnected: Boolean,
    val ringLevelOk: Boolean,
    val wakeDoors: Int? = null,
    val lastRingCall: String? = null,
    val lastRingPath: String? = null,
    val lastRingDoor: String? = null,
    val phoneEndpoint: String? = null,
    val houseSwitch: Boolean,
    val houseGate: Boolean,
    val autoRevokeExempt: Boolean,
    /** This build understands the server's `panel_call` request (`portico.call_everyone`): the server asks only an app that says so. */
    val panelCall: Boolean = true,
) {
    /**
     * The inputs whose change sends a heartbeat at once (section 9: "whenever `front_reason`, `overdue` or any `ready` input changes").
     * The ring fields are not in it: a ring sends its own heartbeat.
     */
    fun readyInputs(): List<Any?> = listOf(
        appVersion, panelMode, endpointOk, frontReason, overdue, overlayOk, fsiOk, microphoneOk, notificationsOk, ringAudible, batteryExempt,
        stayConnected, ringLevelOk, wakeDoors, phoneEndpoint, houseSwitch, houseGate, autoRevokeExempt,
    )
}

/** The server's answer to a heartbeat: [houseOk] as it computed it, and [phoneEndpoint] as it holds it (null: an old server). */
data class HeartbeatReply(val houseOk: Boolean?, val phoneEndpoint: String?)

/** What a heartbeat's reply came to. */
sealed interface HeartbeatOutcome {
    data class Ok(val reply: HeartbeatReply) : HeartbeatOutcome

    /** HA refused: `unknown_command` means the server has no panel support (the driver stops for this connection). */
    data class Error(val code: String) : HeartbeatOutcome

    /** The socket closed first, or the call was never sent. */
    data object Lost : HeartbeatOutcome
}

object PanelHeartbeatCommands {
    const val HEARTBEAT = "portico/wall/heartbeat"

    /** The push payload's id rule, which the server also applies to the call id here (design section 5 item 7). */
    private val ID = Regex("[A-Za-z0-9._:-]{1,128}")
    private val PATHS = setOf("direct", "fsi", "banner", "failed", "filtered")

    /** The body of `portico/wall/heartbeat`; the link adds `type` and `id`. A field with no value is left out. */
    fun heartbeat(f: PanelFacts): JsonObject = buildJsonObject {
        put("app_version", f.appVersion.take(64))
        put("panel_mode", f.panelMode)
        put("endpoint_ok", f.endpointOk)
        put("foreground", f.foreground)
        put("front_reason", f.frontReason.wire)
        put("overdue", f.overdue)
        put("overlay_ok", f.overlayOk)
        put("fsi_ok", f.fsiOk)
        put("microphone_ok", f.microphoneOk)
        put("notifications_ok", f.notificationsOk)
        put("ring_audible", f.ringAudible)
        put("battery_exempt", f.batteryExempt)
        put("stay_connected", f.stayConnected)
        put("ring_level_ok", f.ringLevelOk)
        if (f.wakeDoors != null) put("wake_doors", f.wakeDoors.coerceIn(0, 999)) else put("wake_all", true)
        f.lastRingCall?.takeIf { ID.matches(it) }?.let { put("last_ring_call", it) }
        f.lastRingPath?.takeIf { it in PATHS }?.let { put("last_ring_path", it) }
        f.lastRingDoor?.takeIf { ID.matches(it) }?.let { put("last_ring_door", it) }
        f.phoneEndpoint?.takeIf { ID.matches(it) }?.let { put("phone_endpoint", it) }
        put("house_switch", f.houseSwitch)
        put("house_gate", f.houseGate)
        put("auto_revoke_exempt", f.autoRevokeExempt)
        put("panel_call", f.panelCall)
    }

    /**
     * The reply `{house_ok, phone_endpoint}`. A missing or mistyped field is null, never a guess: a reply without `phone_endpoint` is
     * what an older server (or a server that does not support panel house calls) sends.
     */
    fun outcome(frame: HaInbound.Result?): HeartbeatOutcome {
        if (frame == null) return HeartbeatOutcome.Lost
        if (!frame.success) return HeartbeatOutcome.Error(frame.error?.code?.takeIf { it.isNotBlank() } ?: "error")
        val o = frame.result as? JsonObject
        val ok = (o?.get("house_ok") as? JsonPrimitive)?.booleanOrNull
        val pe = (o?.get("phone_endpoint") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { ID.matches(it) }
        return HeartbeatOutcome.Ok(HeartbeatReply(ok, pe))
    }

    /** True when the server's answer says the app's cached `phone_endpoint` is stale, so the content should be fetched again. */
    fun needsRefetch(reply: HeartbeatReply, cachedPhoneEndpoint: String?): Boolean =
        reply.phoneEndpoint != null && reply.phoneEndpoint != cachedPhoneEndpoint
}

/**
 * Sends the heartbeat (design section 9). Rules: at once on [onSocketOpen]; again whenever [kick] is called (a ring was shown or
 * filtered, `front_reason` or a `ready` input changed), at most one per [MIN_GAP_MS] with the last state winning (the facts are read when
 * the send happens, not when it was asked for); otherwise every [PERIOD_MS]. [inputsMayHaveChanged] is the cheap check the app calls
 * when something it cannot be told about may have changed (a screen came forward, a permission dialog closed): it sends only when
 * [PanelFacts.readyInputs] differ from the last send. An `unknown_command` answer stops the driver until the next [onSocketOpen] (a
 * server without panel support must not be asked every minute). Everything runs on [schedule]'s thread; [send] must not block.
 */
class PanelHeartbeatDriver(
    private val enabled: () -> Boolean,
    private val facts: () -> PanelFacts,
    /** Puts the body on the socket and calls back with the outcome; false when the socket is not ready (nothing was sent). */
    private val send: (JsonObject, (HeartbeatOutcome) -> Unit) -> Boolean,
    /** Runs a task later; the returned handle cancels it. */
    private val schedule: (delayMs: Long, task: () -> Unit) -> () -> Unit,
    private val clockMs: () -> Long,
    private val onOutcome: (HeartbeatOutcome) -> Unit = {},
) {
    private val lock = Any()
    private var lastSentAt = Long.MIN_VALUE / 2
    private var lastInputs: List<Any?>? = null
    private var timer: (() -> Unit)? = null
    private var kickTimer: (() -> Unit)? = null
    private var unsupported = false

    /** The socket is authenticated and subscribed: a new connection, so a server that lacked the command is asked again. */
    fun onSocketOpen() {
        synchronized(lock) { unsupported = false }
        sendNow()
    }

    /** The socket closed: nothing is sent or scheduled until it opens again. */
    fun onSocketClosed() {
        synchronized(lock) {
            timer?.invoke()
            timer = null
            kickTimer?.invoke()
            kickTimer = null
        }
    }

    /** Something worth reporting happened: send, but not more than once per [MIN_GAP_MS] (the later facts win). */
    fun kick() {
        val delay: Long
        synchronized(lock) {
            if (unsupported || !enabled()) return
            if (kickTimer != null) return // one is already waiting, and it reads the facts when it fires
            delay = (lastSentAt + MIN_GAP_MS - clockMs()).coerceAtLeast(0)
            if (delay == 0L) {
                // fall through to the send below, outside the lock
            } else {
                kickTimer = schedule(delay) {
                    synchronized(lock) { kickTimer = null }
                    sendNow()
                }
                return
            }
        }
        sendNow()
    }

    /** Sends only when the ready inputs differ from the last heartbeat's. */
    fun inputsMayHaveChanged() {
        val now = try { facts().readyInputs() } catch (_: Exception) { return }
        if (synchronized(lock) { now == lastInputs }) return
        kick()
    }

    private fun sendNow() {
        val body: JsonObject
        synchronized(lock) {
            if (unsupported || !enabled()) return
            val f = try { facts() } catch (_: Exception) { return }
            body = PanelHeartbeatCommands.heartbeat(f)
            lastSentAt = clockMs()
            lastInputs = f.readyInputs()
            timer?.invoke()
            timer = schedule(PERIOD_MS) {
                synchronized(lock) { timer = null }
                sendNow()
            }
        }
        val sent = send(body) { outcome ->
            if (outcome is HeartbeatOutcome.Error && outcome.code == "unknown_command") {
                synchronized(lock) {
                    unsupported = true
                    timer?.invoke()
                    timer = null
                }
            }
            try { onOutcome(outcome) } catch (_: Exception) { }
        }
        if (!sent) {
            // not ready: the next socket open sends again, and nothing should keep ticking against a closed socket
            synchronized(lock) {
                timer?.invoke()
                timer = null
                lastInputs = null
            }
        }
    }

    companion object {
        const val PERIOD_MS = 60_000L
        const val MIN_GAP_MS = 2_000L
    }
}
