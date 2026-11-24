package com.meda.portico.core.ha

import com.meda.portico.core.call.HouseCallEvent
import com.meda.portico.core.call.HouseEventSink
import com.meda.portico.core.ha.HaLog.Level
import com.meda.portico.core.connlog.ReasonWords
import com.meda.portico.core.push.PushJournal
import com.meda.portico.core.reg.RegEffect
import com.meda.portico.core.reg.RegStatus
import com.meda.portico.core.reg.RegValue
import com.meda.portico.core.reg.RegistrationConfig
import com.meda.portico.core.reg.RegistrationMachine

/** Firebase, as the registration needs it. PR-E implements it over `FirebaseMessaging`. */
interface RegistrationSource {
    /** `FirebaseMessaging.register()`; the FID comes back via [PorticoLink.onRegistrationValue]. */
    fun requestFid()

    /** `getToken()`; the token comes back via [PorticoLink.onRegistrationValue] as kind `token`. */
    fun requestToken()
}

/**
 * The protocol core for one phone: the long-lived glue between a WebSocket (any [WsTransport]),
 * [HaProtocol] (one per connection), the [RegistrationMachine], the [DoorsCache] and the
 * `portico/calls/subscribe` -> [CallStateSink] path. Transport-agnostic and single-threaded: the
 * caller (SignalingClient) serialises every call onto one thread.
 *
 * Per connection (contract 3): `auth_required` -> auth -> `auth_ok` -> `portico/version` -> (result: contract 3) ->
 * `portico/doors` -> (result) -> `portico/push/register` (via the machine), `portico/calls/subscribe`,
 * `portico/content/subscribe` and `portico/content/get`. A version that is not 3 is a [ContractMismatch]: nothing else
 * is sent, nothing is registered, the socket is closed and the client retries with backoff.
 */
class PorticoLink(
    private val machine: RegistrationMachine,
    val doors: DoorsCache,
    private val sink: CallStateSink,
    private val source: RegistrationSource,
    private val listener: Listener = Listener.NONE,
    private val log: HaLog = HaLog.NONE,
    /** `talk_state` events (P3-C): the Talk session's only feed from the calls stream. Inert without a session. */
    private val talkSink: TalkStateSink = TalkStateSink.NONE,
    /** `house_call` events (M7 3b-A): the placing phone's call session follows them. The server sends none while house calls are off. */
    private val houseSink: HouseEventSink = HouseEventSink.NONE,
    /** `panel_call` events (DEV-807 follow-up): the server asks this panel to place the Everyone call. Inert outside Panel mode (the coordinator refuses). */
    private val panelCallSink: com.meda.portico.core.panel.PanelCallSink = com.meda.portico.core.panel.PanelCallSink.NONE,
    /** DEV-764: the journal the handshake steps are written to (`net link-*`). Write-only observation; null in tests that do not look. */
    private val journal: PushJournal? = null,
    /** Monotonic milliseconds for the handshake's elapsed times. */
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /**
     * DEV-807 (Panel mode): the screen slug the panel names in its content calls (`endpoint=<slug>`: content get / subscribe / run /
     * snapshot / live), or null (a phone: it never names one). Read at each send, so a slug adopted or dropped applies to the next call.
     */
    private val endpointOf: () -> String? = { null },
) {
    /**
     * DEV-764: how far this connection's handshake has got, readable from any thread (the answer path reads it at a press). Each stage is
     * the milliseconds after the socket opened at which it was answered, or null while it has not been.
     */
    class Facts(
        val open: Boolean,
        val sinceOpenMs: Long?,
        val versionMs: Long?,
        val doorsMs: Long?,
        val callsMs: Long?,
        val contentMs: Long?,
    ) {
        /** The fields every command and press line carries. */
        fun fields(): List<Pair<String, Any?>> = listOf(
            "up" to sinceOpenMs, "ver" to (versionMs != null), "doors" to (doorsMs != null),
            "calls" to (callsMs != null), "cont" to (contentMs != null),
        )
    }

    /** Commands whose events matter (away call audio's `portico/media_open`), by command id. */
    private class Stream(val onEvent: (kotlinx.serialization.json.JsonObject) -> Unit, val onLost: () -> Unit)

    /**
     * DEV-811: everything that belongs to ONE connection. The link works on [cur]; [primary] is the connection the app uses. A link race
     * (see [openCandidate]) builds a second one beside it, runs its handshake with [cur] pointed at it, and swaps it in
     * ([promoteCandidate]) or throws it away. Link thread only, except the volatile timestamps and [transport] (read by [facts]).
     */
    private class Conn(var candidate: Boolean) {
        @Volatile var transport: WsTransport? = null
        var protocol: HaProtocol? = null
        val watchdog = LinkWatchdog()
        var watchdogPings = 0
        @Volatile var openedAtMs = -1L
        @Volatile var versionAtMs = -1L
        @Volatile var doorsAtMs = -1L
        @Volatile var callsAtMs = -1L
        @Volatile var contentAtMs = -1L
        val pending = HashMap<Int, Pending>()
        val quietIds = LinkedHashSet<Int>()
        val pendingArg = HashMap<Int, String>()
        val callReplies = HashMap<Int, (HaInbound.Result?) -> Unit>()
        val streams = HashMap<Int, Stream>()
        var contentRev: String? = null
        var contentEndpoint: String? = null
        var callsSubscriptionId: Int? = null
        var contentSubscriptionId: Int? = null
        var sessionStarted = false
        var contractOk = false
        var contractMismatch: ContractMismatch? = null
        val doorsAsked = HashSet<String>()
        var doorRefreshes = 0

        /** Candidate only: the server's version, the two subscription answers, a refusal, and the call events heard before the swap. */
        var haVersion: String? = null
        var callsAnswered = false
        var contentAnswered = false
        var failed = false
        var doorList: List<Door>? = null
        var contentRefused: ContentCommands.ErrorKind? = null
        val heard = ArrayList<kotlinx.serialization.json.JsonObject>()
    }

    @Volatile private var primary = Conn(false)
    private var cur: Conn = primary
    private var candidateConn: Conn? = null

    private val watchdog get() = cur.watchdog
    private var watchdogPings: Int
        get() = cur.watchdogPings
        set(v) { cur.watchdogPings = v }
    private var openedAtMs: Long
        get() = cur.openedAtMs
        set(v) { cur.openedAtMs = v }
    private var versionAtMs: Long
        get() = cur.versionAtMs
        set(v) { cur.versionAtMs = v }
    private var doorsAtMs: Long
        get() = cur.doorsAtMs
        set(v) { cur.doorsAtMs = v }
    private var callsAtMs: Long
        get() = cur.callsAtMs
        set(v) { cur.callsAtMs = v }
    private var contentAtMs: Long
        get() = cur.contentAtMs
        set(v) { cur.contentAtMs = v }

    /** DEV-764: the handshake's state now, for a press line. Reads volatile fields of the PRIMARY connection only; safe from any thread. */
    fun facts(): Facts {
        val c = primary
        val o = c.openedAtMs
        if (o < 0 || c.transport == null) return Facts(false, null, null, null, null, null)
        fun at(v: Long): Long? = if (v >= 0) v - o else null
        return Facts(true, clockMs() - o, at(c.versionAtMs), at(c.doorsAtMs), at(c.callsAtMs), at(c.contentAtMs))
    }

    private fun since(): Long? = openedAtMs.takeIf { it >= 0 }?.let { clockMs() - it }

    private fun diag(event: String, vararg fields: Pair<String, Any?>) {
        try {
            journal?.diag("net", if (cur.candidate) "cand-$event" else event, fields.toList())
        } catch (_: Throwable) {
        }
    }

    interface Listener {
        fun onAuthenticated(haVersion: String?) {}
        fun onAuthRejected(message: String?) {}

        /**
         * `portico/version` did not answer contract 3. The link is closing the socket; the client retries and asks again
         * (a per-connection state, R7-05). Called once per connection.
         */
        fun onContractMismatch(mismatch: ContractMismatch) {}
        fun onRegistrationStatus(status: RegStatus) {}
        fun onPushStatus(outcome: PushCommands.Outcome) {}

        /** `portico/content/get` answered: [ContentCommands.Outcome.Profile], or a failure (an older server has no content). */
        fun onContent(outcome: ContentCommands.Outcome) {}

        /** DEV-807: `portico/content/subscribe` was refused ([kind]); with a slug named, `unauthorized` means the slug is stale. */
        fun onContentSubscribeRefused(kind: ContentCommands.ErrorKind) {}

        /** A button's `portico/content/run` answered, or the socket closed first ([ContentCommands.Outcome.Lost]). */
        fun onContentRun(actionId: String, outcome: ContentCommands.Outcome) {}

        /** A camera's `portico/content/snapshot` answered (a [ContentCommands.Outcome.Snapshot]), refused, or lost. */
        fun onSnapshot(cameraId: String, outcome: ContentCommands.Outcome) {}

        /** The socket closed (after any in-flight run or snapshot was reported [ContentCommands.Outcome.Lost]). */
        fun onDisconnected() {}

        companion object {
            val NONE = object : Listener {}
        }
    }

    private enum class Pending {
        VERSION, DOORS_INITIAL, DOORS_REFRESH, CALLS_SUBSCRIBE, CONTENT_SUBSCRIBE,
        REGISTER, UNREGISTER, STATUS, CONTENT_GET, CONTENT_RUN, CONTENT_SNAPSHOT, CALL,
    }

    private companion object {
        /** Most quiet ids remembered; a lost answer (socket drop) must not grow the set. */
        const val MAX_QUIET_IDS = 256

        /** Most `portico/doors` refreshes one connection asks for because an event named an unknown door. */
        const val MAX_DOOR_REFRESHES = 8

        /** DEV-811: most call events a candidate connection keeps while it waits to win (one snapshot per door, then the changes). */
        const val MAX_HEARD = 128

        /** DEV-807: the commands that take `endpoint` (design section 5: `content/get`, `run`, `snapshot`, `live` and `content/subscribe`). */
        val NAMING_COMMANDS = setOf(ContentCommands.GET, ContentCommands.RUN, ContentCommands.SNAPSHOT, Handshake.CONTENT_SUBSCRIBE, LiveCommands.LIVE)
    }

    val tracker = SubscribeTracker(doors)

    private var transport: WsTransport?
        get() = cur.transport
        set(v) { cur.transport = v }
    private var protocol: HaProtocol?
        get() = cur.protocol
        set(v) { cur.protocol = v }
    private val pending get() = cur.pending

    /** Ids of fire-and-forget commands ([sendQuiet]) whose result is expected and not interesting; bounded. */
    private val quietIds get() = cur.quietIds

    /** The action or camera id a pending content run or snapshot is about (a result carries none). */
    private val pendingArg get() = cur.pendingArg

    /** The answer path's commands in flight (`portico/answer`, `portico/webrtc_offer`, `portico/hangup`), by command id. */
    private val callReplies get() = cur.callReplies

    private val streams get() = cur.streams

    /** The `rev` of the last content profile received on this connection; a `content_changed` for the same one is a no-op. */
    private var contentRev: String?
        get() = cur.contentRev
        set(v) { cur.contentRev = v }

    /** The endpoint id the last content profile came from (`content/get` answers with it); a change for another one is not ours. */
    private var contentEndpoint: String?
        get() = cur.contentEndpoint
        set(v) { cur.contentEndpoint = v }
    private var callsSubscriptionId: Int?
        get() = cur.callsSubscriptionId
        set(v) { cur.callsSubscriptionId = v }
    private var contentSubscriptionId: Int?
        get() = cur.contentSubscriptionId
        set(v) { cur.contentSubscriptionId = v }
    private var sessionStarted: Boolean
        get() = cur.sessionStarted
        set(v) { cur.sessionStarted = v }

    /** `portico/version` answered contract 3 on this connection. */
    private var contractOk: Boolean
        get() = cur.contractOk
        set(v) { cur.contractOk = v }

    /**
     * The mismatch this connection found, or null. PER CONNECTION (R7-05): [onSocketOpen] and [onSocketClosed] clear it,
     * and the next connect asks `portico/version` again. Not a terminal state like [authRejected].
     */
    var contractMismatch: ContractMismatch?
        get() = cur.contractMismatch
        private set(v) { cur.contractMismatch = v }

    /** Door ids a calls event named that the cache did not know, already answered by a refresh on this connection. */
    private val doorsAsked get() = cur.doorsAsked
    private var doorRefreshes: Int
        get() = cur.doorRefreshes
        set(v) { cur.doorRefreshes = v }
    private var lastStatus: RegStatus? = null

    /** True when the last connection's token was refused (`auth_invalid`): do not reconnect until Setup changes. */
    var authRejected: Boolean = false
        private set

    /** True from the moment `portico/version` answers contract 3 (after `auth_ok`) until the socket closes. */
    val isReady: Boolean get() = protocol?.phase == HaProtocol.Phase.READY && transport != null && contractOk

    val registrationStatus: RegStatus get() = machine.status

    // ---- socket side -----------------------------------------------------------------------

    fun onSocketOpen(transport: WsTransport, accessToken: String) {
        if (this.transport != null) onSocketClosed("replaced by a new socket")
        this.transport = transport
        this.protocol = HaProtocol(accessToken)
        authRejected = false
        contractOk = false
        contractMismatch = null
        openedAtMs = clockMs()
        versionAtMs = -1L
        doorsAtMs = -1L
        callsAtMs = -1L
        contentAtMs = -1L
        watchdog.reset(clockMs())
        watchdogPings = 0
        diag("link-open")
        log.log(Level.DEBUG, "ws open, awaiting auth_required")
    }

    fun onSocketText(text: String) {
        val proto = protocol ?: return
        watchdog.onRx(clockMs()) // any frame proves the link; a `pong` needs nothing more
        for (action in proto.onText(text)) {
            when (action) {
                is HaProtocol.Action.Send -> transport?.send(action.text)
                is HaProtocol.Action.Authenticated -> {
                    log.log(Level.INFO, "auth_ok (HA ${action.haVersion ?: "?"})")
                    diag("link-auth", "ms" to since())
                    if (cur.candidate) cur.haVersion = action.haVersion else listener.onAuthenticated(action.haVersion)
                    send(Handshake.VERSION, Pending.VERSION)
                }
                is HaProtocol.Action.AuthRejected -> {
                    log.log(Level.WARN, "auth_invalid: ${action.message ?: ""}")
                    diag("link-auth-rejected", "ms" to since())
                    if (cur.candidate) {
                        cur.failed = true // a candidate that is refused is just dropped: it must not stop the working link's retries
                    } else {
                        authRejected = true
                        listener.onAuthRejected(action.message)
                    }
                    transport?.close(1000, "auth_invalid")
                }
                is HaProtocol.Action.Deliver -> deliver(action.frame)
                is HaProtocol.Action.Ignored -> log.log(Level.DEBUG, "ignored: ${action.reason}")
            }
        }
    }

    fun onSocketClosed(reason: String) {
        if (transport == null && protocol == null) return
        log.log(Level.INFO, "ws closed: $reason")
        diag("link-closed", "why" to ReasonWords.of(reason), "pend" to pending.size, *facts().fields().toTypedArray())
        openedAtMs = -1L
        transport = null
        protocol = null
        val unanswered = pending.entries.filter { it.value == Pending.CONTENT_RUN || it.value == Pending.CONTENT_SNAPSHOT }
            .map { it.value to pendingArg[it.key] }
        pending.clear()
        quietIds.clear()
        pendingArg.clear()
        contentRev = null
        contentEndpoint = null
        doorsAsked.clear()
        doorRefreshes = 0
        contractOk = false
        contractMismatch = null // per connection: the next one asks again
        val lostCalls = callReplies.values.toList()
        callReplies.clear()
        val lostStreams = streams.values.toList()
        streams.clear()
        for ((kind, arg) in unanswered) {
            if (arg == null) continue
            if (kind == Pending.CONTENT_RUN) listener.onContentRun(arg, ContentCommands.Outcome.Lost)
            else listener.onSnapshot(arg, ContentCommands.Outcome.Lost)
        }
        // The answer path: every command still waiting is told the socket closed first (null).
        for (reply in lostCalls) {
            try {
                reply(null)
            } catch (ex: Exception) {
                log.log(Level.ERROR, "call reply failed on close: ${ex.javaClass.simpleName}")
            }
        }
        for (stream in lostStreams) {
            try {
                stream.onLost()
            } catch (ex: Exception) {
                log.log(Level.ERROR, "stream close failed: ${ex.javaClass.simpleName}")
            }
        }
        listener.onDisconnected()
        callsSubscriptionId = null
        contentSubscriptionId = null
        sessionStarted = false
        run(machine.onDisconnected())
    }

    // ---- the link race (DEV-811) ------------------------------------------------------------

    /** A second connection is being built beside the working one. */
    val hasCandidate: Boolean get() = candidateConn != null

    /** The candidate has authenticated, passed the contract check and has both subscriptions answered: it can take over. */
    val candidateReady: Boolean get() = candidateConn?.let { it.callsAnswered && it.contentAnswered && !it.failed } == true

    /** The candidate was refused or failed its handshake (it is not usable and the caller should close it). */
    val candidateFailed: Boolean get() = candidateConn?.failed == true

    /**
     * DEV-811: [transport] was opened beside the working connection (the link kept it, it was not dropped). Its handshake runs through
     * [onCandidateText] and is the same as a normal connect's, except that it registers nothing, reads no profile and delivers nothing.
     */
    fun openCandidate(transport: WsTransport, accessToken: String) {
        val c = Conn(true)
        c.transport = transport
        c.protocol = HaProtocol(accessToken)
        c.openedAtMs = clockMs()
        c.watchdog.reset(clockMs())
        candidateConn = c
        onConn(c) { diag("link-open") }
    }

    fun onCandidateText(text: String) {
        val c = candidateConn ?: return
        onConn(c) { onSocketText(text) }
    }

    /** The candidate is over (it closed, failed or lost): forgets it. The caller closes its transport. */
    fun dropCandidate() {
        candidateConn = null
    }

    /**
     * DEV-811: the candidate wins. If the old connection is still open it is closed the way any close is: every command in flight on it is
     * answered `null` (Lost; the answer path sends it again on the new one), a content run or snapshot is told Lost, the listener and the
     * registration machine see a disconnect, exactly as a reconnect would. Then the candidate becomes THE connection: the listener sees it
     * authenticated, the push is registered and the profile read again as on any connect, and the call events it heard are replayed
     * (those the old connection already delivered are dropped by [SubscribeTracker.isRepeat]). False: nothing to promote or not ready.
     * The caller closes the old transport and hands the path over ([WsTransport.promote]).
     */
    fun promoteCandidate(): Boolean {
        val c = candidateConn ?: return false
        if (!candidateReady) return false
        candidateConn = null
        val old = primary
        if (old.transport != null) {
            cur = old
            onSocketClosed("link race swap")
        }
        c.candidate = false
        c.watchdog.copyWindowFrom(old.watchdog) // the ring and Answer windows are the person's, not the socket's
        c.doorList?.let { doors.replaceAll(it) }
        primary = c
        cur = c
        diag("link-swap")
        listener.onAuthenticated(c.haVersion)
        run(machine.onConnected())
        requestContent()
        c.contentRefused?.let { k ->
            try { listener.onContentSubscribeRefused(k) } catch (ex: Exception) { log.log(Level.ERROR, "listener failed: ${ex.javaClass.simpleName}") }
        }
        val heard = collapseHeard(c.heard.toList())
        c.heard.clear()
        for (e in heard) {
            if (tracker.isRepeat(e)) continue
            onCallsEvent(e)
        }
        return true
    }

    /**
     * Everything the candidate's calls subscription said before the swap, reduced to the latest word per call (and per other event
     * kind): a ring that came and went is nothing, and the old link already delivered most of the rest ([SubscribeTracker.isRepeat]).
     */
    private fun collapseHeard(events: List<kotlinx.serialization.json.JsonObject>): List<kotlinx.serialization.json.JsonObject> {
        val lastAt = LinkedHashMap<String, Int>()
        events.forEachIndexed { i, e -> lastAt["${e.str("type")}|${e.str("call_id")}|${e.str("door_id")}"] = i }
        return lastAt.values.sorted().map { events[it] }.filter { e ->
            val ended = e.str("type") == "call_state_changed" && e.str("state") == "ended"
            !(ended && tracker.followedCall(e.str("door_id") ?: "") != e.str("call_id")) // nothing this phone followed: nothing to end
        }
    }

    /** How long the working connection's ping has gone unanswered (ms), or -1. */
    fun pingAgeMs(): Long = primary.watchdog.pingAgeMs(clockMs())

    /** What a race start journals: why the strict rule tripped (`fast` reason or null) and how long the link has been silent. */
    fun raceFacts(): Pair<String?, Long> {
        val now = clockMs()
        return primary.watchdog.fastReason(now) to primary.watchdog.silentMs(now)
    }

    private inline fun <T> onConn(c: Conn, block: () -> T): T {
        val saved = cur
        cur = c
        try {
            return block()
        } finally {
            cur = saved
        }
    }

    // ---- app side --------------------------------------------------------------------------

    /** Once per process: asks Firebase for the current FID (an unchanged value is a no-op later). */
    fun start() = run(machine.start())
    fun onRegistrationValue(value: RegValue) = run(machine.onRegistrationValue(value))
    fun onRegistrationSourceFailed() = run(machine.onRegistrationSourceFailed())

    /** Drives the machine's clock-based rules (token fallback); call it every few seconds. */
    fun tick() = run(machine.tick())
    fun setPushEnabled(enabled: Boolean) = run(machine.setPushEnabled(enabled))
    fun onConfigChanged(config: RegistrationConfig) = run(machine.onConfigChanged(config))
    fun signOut() = run(machine.signOut())

    /** Asks for `portico/push/status`; the answer goes to [Listener.onPushStatus]. False if not connected. */
    fun requestPushStatus(): Boolean = send(PushCommands.STATUS, Pending.STATUS) != null

    /** Asks for the phone's content profile; the answer goes to [Listener.onContent]. False if not connected. */
    fun requestContent(): Boolean = send(ContentCommands.GET, Pending.CONTENT_GET, ContentCommands.get()) != null

    /**
     * Runs one of the profile's buttons by its id; the answer goes to [Listener.onContentRun].
     * False (and nothing sent) if not connected or the id is unusable: the caller says so, nothing is queued.
     */
    fun runAction(actionId: String): Boolean {
        val body = try {
            ContentCommands.run(actionId)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val id = send(ContentCommands.RUN, Pending.CONTENT_RUN, body) ?: return false
        pendingArg[id] = actionId
        return true
    }

    /** Asks for one still from one of the profile's cameras ([width] null: the server's default); the answer goes to [Listener.onSnapshot]. */
    fun requestSnapshot(cameraId: String, width: Int? = null): Boolean {
        val body = try {
            ContentCommands.snapshot(cameraId, width)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val id = send(ContentCommands.SNAPSHOT, Pending.CONTENT_SNAPSHOT, body) ?: return false
        pendingArg[id] = cameraId
        return true
    }

    /**
     * Sends one of the answer path's commands ([type] and [body] from `AnswerCommands`) and calls
     * [onReply] with HA's result, or null if the socket closes first. False (and [onReply] never
     * called) when the socket is not ready: the caller reports "not sent". Not queued, not retried.
     */
    fun sendCall(type: String, body: kotlinx.serialization.json.JsonObject, onReply: (HaInbound.Result?) -> Unit): Boolean {
        val id = send(type, Pending.CALL, body) ?: return false
        callReplies[id] = onReply
        return true
    }

    /**
     * Sends [type] and keeps listening: [onReply] gets the result (null if the socket closes first), then
     * every event carrying the command's id goes to [onEvent]; [onLost] runs if the socket closes under it.
     * Returns the command id, or null when the socket is not ready (nothing was sent, no callback will run).
     */
    fun openStream(
        type: String,
        body: kotlinx.serialization.json.JsonObject,
        onReply: (HaInbound.Result?) -> Unit,
        onEvent: (kotlinx.serialization.json.JsonObject) -> Unit,
        onLost: () -> Unit,
    ): Int? {
        val id = send(type, Pending.CALL, body) ?: return null
        callReplies[id] = onReply
        streams[id] = Stream(onEvent, onLost)
        return id
    }

    /** Stops delivering events for a stream (its channel is closed). */
    fun dropStream(id: Int) {
        streams.remove(id)
    }

    /** Sends a command nothing waits for (an uplink audio frame). False when the socket is not ready. */
    fun sendQuiet(type: String, body: kotlinx.serialization.json.JsonObject): Boolean {
        val proto = protocol ?: return false
        val t = transport ?: return false
        val (id, text) = proto.command(type, body) ?: return false
        if (!t.send(text)) return false
        // HA still answers every command; remember the id so onResult drops the answer without a log line.
        quietIds.add(id)
        if (quietIds.size > MAX_QUIET_IDS) quietIds.remove(quietIds.first())
        return true
    }

    /**
     * DEV-764 aftermath: one liveness step, called every [LinkWatchdog.TICK_MS] on the link thread while the socket is connected.
     * Sends HA's `ping` when the link has been silent too long (or a command got no sign of life) and returns true when the link is
     * DEAD: a ping went unanswered past its deadline. The caller then drops the socket and reconnects at once. [active]: a ring is up
     * or the screen is on (ping every ~10 s instead of ~30 s). Writes one `net link-watchdog` journal line on a trip, none otherwise.
     */
    fun watchdogStep(active: Boolean): Boolean = watchdogVerdict(active, { false }, mayRace = false) == LinkWatchdog.Verdict.DEAD

    /**
     * [watchdogStep] with the link race (DEV-811). The strict rule is applied first; only when it says DEAD is [mobile] asked (never on a
     * tick that decides nothing). On a mobile or tailnet link ([mobile], or [mobileKnown] while a candidate exists) the strict deadline gives
     * [LinkWatchdog.Verdict.RACE] once (open a second connection, keep this one, unless [mayRace] is false) and DEAD only after the longer
     * mobile deadline. On Wi-Fi it is DEAD at once. Returns NONE, RACE or DEAD (a PING is sent here and reported as NONE).
     */
    fun watchdogVerdict(active: Boolean, mobile: () -> Boolean, mayRace: Boolean, mobileKnown: Boolean = false): LinkWatchdog.Verdict {
        val none = LinkWatchdog.Verdict.NONE
        val proto = protocol ?: return none
        val t = transport ?: return none
        if (proto.phase != HaProtocol.Phase.READY) return none // before auth_ok the connect's own timeouts apply
        val now = clockMs()
        var verdict = watchdog.decide(now, active, race = mobileKnown, mayRace = mayRace)
        // the strict rule says dead: only now is the network asked (never on a tick that decides nothing)
        if (!mobileKnown && verdict == LinkWatchdog.Verdict.DEAD && (try { mobile() } catch (_: Exception) { false })) {
            verdict = watchdog.decide(now, active, race = true, mayRace = mayRace)
        }
        when (verdict) {
            LinkWatchdog.Verdict.NONE -> return none
            LinkWatchdog.Verdict.PING -> {
                val (_, text) = proto.command("ping") ?: return none
                if (t.send(text)) {
                    watchdog.onPingSent(now)
                    watchdogPings++
                }
                return none
            }
            LinkWatchdog.Verdict.RACE -> return verdict
            LinkWatchdog.Verdict.DEAD -> {
                diag(
                    "link-watchdog", "silent" to watchdog.silentMs(now), "pings" to watchdogPings,
                    "cmd" to watchdog.commandWaiting, "active" to active, "fast" to watchdog.fastReason(now), "pend" to pending.size, *facts().fields().toTypedArray(),
                )
                return verdict
            }
        }
    }

    /** DEV-811: media frames ride this socket (an away call's `portico/media_open` stream): a swap would cut them, so no race is started. */
    fun hasOpenStreams(): Boolean = primary.streams.isNotEmpty()

    /** One `net link-race-<what>` journal line: `start`, `won` (`by` = old or new, `ms` = since the race started) or `gaveup`. Fixed words, no ids, no addresses. */
    fun raceNote(what: String, vararg fields: Pair<String, Any?>) {
        try {
            journal?.diag("net", "link-race-$what", fields.toList())
        } catch (_: Throwable) {
        }
    }

    /** An Answer was pressed: the watchdog runs its fast rule for [LinkWatchdog.FAST_WINDOW_MS]. Link thread only. */
    fun answerFastWatch() = watchdog.answerPressed(clockMs())

    /**
     * A ring started: the fast rule is on until it ends, and a link whose last frame is older than ~1 s is pinged AT ONCE (the person may
     * press Answer any moment, and a link that is not proven alive should be found out now). Link thread only.
     */
    fun ringFastWatch() {
        val now = clockMs()
        watchdog.ringStarted(now)
        val proto = protocol ?: return
        val t = transport ?: return
        if (proto.phase != HaProtocol.Phase.READY || !watchdog.needsProof(now)) return
        val (_, text) = proto.command("ping") ?: return
        if (t.send(text)) {
            watchdog.onPingSent(now)
            watchdogPings++
        }
    }

    /** The ring ended (answered, cancelled, timed out): the fast rule stays only for an Answer press's window. Link thread only. */
    fun ringEndedWatch() = watchdog.ringEnded()

    /** True while the fast rule is on (a ring is up, or an Answer was pressed lately). */
    fun fastWatching(): Boolean = watchdog.isFast(clockMs())

    /** True when a command was written and nothing at all has come back since: the link is not proven alive. */
    fun commandUnproven(): Boolean = watchdog.commandWaiting

    fun queuedBytes(): Long = transport?.queueSize() ?: 0L

    override fun toString(): String = "PorticoLink(ready=$isReady, registration=${machine.status}, doors=${doors.all.size})"

    // ---- internals -------------------------------------------------------------------------

    private fun send(type: String, kind: Pending, body: kotlinx.serialization.json.JsonObject? = null): Int? {
        // DEV-811: while a second connection is being built the working one may be dead; a command granted there would be thrown away at
        // the swap (and refused when sent again). Everything but a hangup waits ("not sent": the answer path polls and sends once on the winner).
        if (!cur.candidate && candidateConn != null && !type.endsWith("hangup")) return null
        val proto = protocol ?: return null
        val t = transport ?: return null
        val named = if (body != null) withEndpoint(type, body) else null
        val (id, text) = (if (named == null) proto.command(type) else proto.command(type, named)) ?: return null
        if (!t.send(text)) return null
        watchdog.onCommandSent(clockMs())
        pending[id] = kind
        return id
    }

    /**
     * DEV-807: a panel names its screen in the five content commands that take an `endpoint`. A body that names one already keeps it; a
     * phone (no slug) sends exactly what the builders made.
     */
    private fun withEndpoint(type: String, body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
        if (type !in NAMING_COMMANDS || body.containsKey("endpoint")) return body
        val slug = try { endpointOf() } catch (_: Exception) { null } ?: return body
        if (slug.length !in 1..64) return body
        return kotlinx.serialization.json.JsonObject(body + ("endpoint" to kotlinx.serialization.json.JsonPrimitive(slug)))
    }

    /** DEV-807: sends `portico/content/subscribe` again (after a slug was adopted or dropped); false when the socket is not ready. */
    fun resubscribeContent(): Boolean {
        val id = send(Handshake.CONTENT_SUBSCRIBE, Pending.CONTENT_SUBSCRIBE, ContentCommands.subscribe()) ?: return false
        contentSubscriptionId = id
        return true
    }

    private fun deliver(frame: HaInbound) {
        when (frame) {
            is HaInbound.Result -> onResult(frame)
            is HaInbound.Event -> onEvent(frame)
            else -> Unit
        }
    }

    private fun onResult(r: HaInbound.Result) {
        val kind = pending.remove(r.id)
        if (kind == null) {
            if (quietIds.remove(r.id)) {
                if (!r.success) log.log(Level.DEBUG, "quiet command ${r.id} failed: ${r.error?.code}")
                return
            }
            log.log(Level.DEBUG, "result for unknown id ${r.id}")
            return
        }
        when (kind) {
            Pending.VERSION -> onVersion(r)
            Pending.DOORS_INITIAL, Pending.DOORS_REFRESH -> {
                val list = Handshake.parseDoors(r)
                if (list != null) {
                    if (cur.candidate) cur.doorList = list else doors.replaceAll(list) // a candidate's list is written at the swap
                    log.log(Level.INFO, "doors: ${list.size}")
                    if (kind == Pending.DOORS_INITIAL) doorsAtMs = clockMs()
                    diag(if (kind == Pending.DOORS_INITIAL) "link-doors" else "link-doors-again", "n" to list.size, "ms" to since())
                } else {
                    diag("link-doors-fail", "code" to r.error?.code, "ms" to since())
                    log.log(Level.WARN, "portico/doors failed (${r.error?.code ?: "bad result"}); keeping the cached ${doors.all.size}")
                }
                if (kind == Pending.DOORS_INITIAL) startSession()
            }
            Pending.CALLS_SUBSCRIBE -> {
                if (!r.success) {
                    log.log(Level.WARN, "portico/calls/subscribe refused: ${r.error?.code}")
                    callsSubscriptionId = null
                    if (cur.candidate) cur.failed = true
                } else {
                    callsAtMs = clockMs()
                    cur.callsAnswered = true
                }
                diag("link-calls", "ok" to r.success, "code" to r.error?.code, "ms" to since())
            }
            Pending.CONTENT_SUBSCRIBE -> {
                if (!r.success) {
                    log.log(Level.WARN, "portico/content/subscribe refused: ${r.error?.code}")
                    contentSubscriptionId = null
                    if (cur.candidate) cur.contentRefused = ContentCommands.ErrorKind.of(r.error?.code ?: "") // told to the listener at the swap
                    else try { listener.onContentSubscribeRefused(ContentCommands.ErrorKind.of(r.error?.code ?: "")) } catch (ex: Exception) { log.log(Level.ERROR, "listener failed: ${ex.javaClass.simpleName}") }
                } else {
                    contentAtMs = clockMs()
                }
                cur.contentAnswered = true
                diag("link-content", "ok" to r.success, "code" to r.error?.code, "ms" to since())
            }
            Pending.REGISTER -> run(machine.onRegisterResult(PushCommands.parseRegister(r)))
            Pending.UNREGISTER -> run(machine.onUnregisterResult(PushCommands.parseUnregister(r)))
            Pending.STATUS -> listener.onPushStatus(PushCommands.parseStatus(r))
            Pending.CONTENT_GET -> {
                val outcome = ContentCommands.parseGet(r)
                if (outcome is ContentCommands.Outcome.Profile) {
                    contentRev = outcome.profile.rev
                    contentEndpoint = outcome.profile.endpoint
                } else log.log(Level.WARN, "portico/content/get failed (${(outcome as? ContentCommands.Outcome.Failed)?.code})")
                diag("link-doc", "ok" to (outcome is ContentCommands.Outcome.Profile), "ms" to since())
                listener.onContent(outcome)
            }
            Pending.CONTENT_RUN -> {
                val arg = pendingArg.remove(r.id)
                if (arg != null) listener.onContentRun(arg, ContentCommands.parseRun(r))
            }
            Pending.CONTENT_SNAPSHOT -> {
                val arg = pendingArg.remove(r.id)
                if (arg != null) listener.onSnapshot(arg, ContentCommands.parseSnapshot(r))
            }
            Pending.CALL -> callReplies.remove(r.id)?.let { reply ->
                try {
                    reply(r)
                } catch (ex: Exception) {
                    log.log(Level.ERROR, "call reply failed: ${ex.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * `portico/version` answered. Contract 3 goes on to `portico/doors`; anything else is a [ContractMismatch]:
     * the socket is closed (the client retries with backoff, `WAITING_TO_RETRY`) and nothing is registered or subscribed.
     */
    private fun onVersion(r: HaInbound.Result) {
        when (val v = Handshake.parseVersion(r)) {
            is Handshake.Version.Ok -> {
                contractOk = true
                versionAtMs = clockMs()
                diag("link-version", "ok" to true, "ms" to since())
                log.log(Level.INFO, "contract ${Handshake.CONTRACT} (ui ${v.uiVersion ?: "?"})")
                send(Handshake.DOORS, Pending.DOORS_INITIAL)
            }
            is Handshake.Version.Mismatch -> {
                if (cur.candidate) {
                    cur.failed = true
                    transport?.close(1000, "contract_mismatch")
                    return
                }
                contractMismatch = v.mismatch
                diag("link-version", "ok" to false, "ms" to since())
                log.log(Level.WARN, "contract mismatch: server ${v.mismatch.serverContract ?: "?"} (${v.mismatch.code}), app needs ${Handshake.CONTRACT}; closing to retry")
                try {
                    listener.onContractMismatch(v.mismatch)
                } catch (ex: Exception) {
                    log.log(Level.ERROR, "contract mismatch listener failed: ${ex.javaClass.simpleName}")
                }
                transport?.close(1000, "contract_mismatch")
            }
        }
    }

    private fun startSession() {
        if (sessionStarted) return
        sessionStarted = true
        if (cur.candidate) {
            // the working link already registered the push and read the profile; a candidate only has to be subscribed
            callsSubscriptionId = send(Handshake.CALLS_SUBSCRIBE, Pending.CALLS_SUBSCRIBE)
            contentSubscriptionId = send(Handshake.CONTENT_SUBSCRIBE, Pending.CONTENT_SUBSCRIBE, ContentCommands.subscribe())
            return
        }
        run(machine.onConnected())
        callsSubscriptionId = send(Handshake.CALLS_SUBSCRIBE, Pending.CALLS_SUBSCRIBE)
        contentSubscriptionId = send(Handshake.CONTENT_SUBSCRIBE, Pending.CONTENT_SUBSCRIBE, ContentCommands.subscribe())
        requestContent()
    }

    /**
     * DEV-764 (app fixes): a command was answered `not_subscribed`. If this connection's `portico/calls/subscribe` was REFUSED
     * (the id was cleared) send it again; if it is merely still pending, or already answered, do nothing (a second subscribe would
     * double every event). Link thread only.
     */
    fun ensureCallsSubscribed() {
        if (!sessionStarted || protocol == null || transport == null) return
        if (callsSubscriptionId != null) return
        callsSubscriptionId = send(Handshake.CALLS_SUBSCRIBE, Pending.CALLS_SUBSCRIBE)
        diag("link-calls-again", "ms" to since())
    }

    private fun onEvent(e: HaInbound.Event) {
        if (cur.candidate) {
            // a candidate delivers nothing until it wins: its call events are kept (the snapshot says what is live now) and replayed after the swap
            if (e.id == callsSubscriptionId && cur.heard.size < MAX_HEARD) cur.heard.add(e.event)
            return
        }
        val stream = streams[e.id]
        if (stream != null) {
            try {
                stream.onEvent(e.event)
            } catch (ex: Exception) {
                log.log(Level.ERROR, "stream event failed: ${ex.javaClass.simpleName}")
            }
            return
        }
        when (e.id) {
            contentSubscriptionId -> onContentEvent(e.event)
            callsSubscriptionId -> onCallsEvent(e.event)
            else -> log.log(Level.DEBUG, "event for unknown subscription ${e.id}")
        }
    }

    /** `{type: "content_changed", endpoint, rev}` from `portico/content/subscribe`. */
    private fun onContentEvent(event: kotlinx.serialization.json.JsonObject) {
        val changed = ContentCommands.parseChanged(event) ?: return
        // A phone hears its own endpoint only; a change naming another endpoint is not ours. An unnamed one (an admin's
        // "every change") or one before the first profile (no endpoint known yet) is read as ours.
        val mine = contentEndpoint
        if (changed.endpoint != null && mine != null && changed.endpoint != mine) return
        // The same rev as the profile already held is nothing new; anything else (or none) refetches.
        if (changed.rev == null || changed.rev != contentRev) requestContent()
    }

    /** `portico/calls/subscribe`: the snapshots, then the changes. */
    private fun onCallsEvent(event: kotlinx.serialization.json.JsonObject) {
        parseTalkState(event)?.let { talk ->
            try {
                talkSink.onTalkState(talk)
            } catch (ex: Exception) {
                log.log(Level.ERROR, "talk sink failed: ${ex.javaClass.simpleName}")
            }
        }
        HouseCallEvent.parse(event)?.let { house ->
            try {
                houseSink.onHouseCall(house)
            } catch (ex: Exception) {
                log.log(Level.ERROR, "house sink failed: ${ex.javaClass.simpleName}")
            }
        }
        com.meda.portico.core.panel.PanelCallWire.parse(event)?.let { request ->
            try {
                panelCallSink.onPanelCall(request)
            } catch (ex: Exception) {
                log.log(Level.ERROR, "panel call sink failed: ${ex.javaClass.simpleName}")
            }
        }
        // A roster change (presence, Do Not Disturb, who receives): read the document again, which carries the roster. One at a time.
        if (HouseCallEvent.isRosterChanged(event) && !pending.containsValue(Pending.CONTENT_GET)) requestContent()
        for (call in tracker.onEvent(event)) {
            try {
                sink.onCallEvent(call)
            } catch (ex: Exception) {
                log.log(Level.ERROR, "call sink failed on $call: ${ex.javaClass.simpleName}")
            }
        }
        refreshDoorsIfChanged(event)
    }

    /**
     * Contract 3 has no `devices_changed`: the door set is learned on a connect. A door that is set up (or leaves)
     * while the socket is up shows itself in the calls events instead, a snapshot for the arrival and `unavailable` for
     * the departure, so those ask `portico/doors` once more. Bounded per connection; the cache is the only thing that
     * changes (a ring for the new door in the first moments is silent, the push path's rule for an unknown door).
     */
    private fun refreshDoorsIfChanged(event: kotlinx.serialization.json.JsonObject) {
        val doorId = SubscribeTracker.doorOf(event) ?: return
        val unknown = !doors.knowsDoorId(doorId)
        val left = SubscribeTracker.doorLeft(event) != null
        // A departure of a door the cache has already forgotten, or an event for a known door, changes nothing.
        if (left == unknown) return
        if (!left && doorId in doorsAsked) return
        if (doorRefreshes >= MAX_DOOR_REFRESHES) return
        if (pending.containsValue(Pending.DOORS_REFRESH)) return // one at a time: this door is asked about at its next event
        if (!left) doorsAsked.add(doorId)
        doorRefreshes++
        send(Handshake.DOORS, Pending.DOORS_REFRESH)
    }

    private fun run(effects: List<RegEffect>) {
        for (effect in effects) {
            when (effect) {
                is RegEffect.SendRegister -> {
                    val c = effect.config
                    val body = try {
                        PushCommands.register(
                            installationId = c.installationId,
                            kind = effect.value.kind.wire,
                            registration = effect.value.value,
                            fcmProjectId = c.fcmProjectId ?: "",
                            appVersion = c.appVersion,
                            packageName = c.packageName,
                        )
                    } catch (ex: IllegalArgumentException) {
                        // Refused before the wire (plan §3.2): never send what the server would reject.
                        log.log(Level.ERROR, "register refused locally: ${ex.message}")
                        run(machine.onRegisterResult(PushCommands.Outcome.Failed(PushCommands.ErrorKind.OTHER, "refused_locally", ex.message ?: "")))
                        continue
                    }
                    log.log(Level.INFO, "register ${effect.value}")
                    if (send(PushCommands.REGISTER, Pending.REGISTER, body) == null) {
                        run(machine.onRegisterResult(PushCommands.Outcome.Lost))
                    }
                }
                is RegEffect.SendUnregister -> {
                    log.log(Level.INFO, "unregister")
                    if (send(PushCommands.UNREGISTER, Pending.UNREGISTER, PushCommands.unregister(effect.installationId)) == null) {
                        run(machine.onUnregisterResult(PushCommands.Outcome.Lost))
                    }
                }
                RegEffect.RequestFid -> source.requestFid()
                RegEffect.RequestToken -> source.requestToken()
            }
        }
        val s = machine.status
        if (s != lastStatus) {
            lastStatus = s
            log.log(Level.INFO, "registration: $s")
            listener.onRegistrationStatus(s)
        }
    }
}
