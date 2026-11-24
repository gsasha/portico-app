package com.meda.portico.signaling

import com.meda.portico.core.auth.AccessTokens
import com.meda.portico.core.ha.Backoff
import com.meda.portico.core.ha.Connector
import com.meda.portico.core.ha.HaConnectionConfig
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.HaLog.Level
import com.meda.portico.core.ha.HaUrls
import com.meda.portico.core.ha.LinkWatchdog
import com.meda.portico.core.ha.PorticoLink
import com.meda.portico.core.ha.WsListener
import com.meda.portico.core.ha.WsTransport
import com.meda.portico.core.net.UrlCheck
import com.meda.portico.core.net.UrlPolicy
import com.meda.portico.core.reg.NotConnectedTimer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The phone's WebSocket to Home Assistant (design §9.5, P2 plan §3.2): HA's auth handshake,
 * `portico/version` (contract 3), `portico/doors`, `portico/push/register` on every connect, and
 * `portico/calls/subscribe` and `portico/content/subscribe`, all run by the pure [PorticoLink] in
 * `:core`. This class only owns the socket's lifecycle: connect through a [Connector] (P2:
 * [OkHttpConnector], direct `ha_url`), reconnect with a capped [Backoff], and stop for good on
 * `auth_invalid` until [reconnect] (a Setup save). A server on another contract is NOT terminal:
 * the socket is dropped and the client stays in its backoff loop ([State.WAITING_TO_RETRY]), asking
 * `portico/version` again on every connect (R7-05).
 *
 * Threading: every call into [PorticoLink] runs on one executor thread; app events (an FID
 * rotation, the push switch, sign-out) go through [post]. No `android.*` imports, so the whole
 * client is exercised on the JVM against MockWebServer.
 *
 * PR-J: every connect re-checks the stored `ha_url` with `:core`'s [UrlPolicy] (https always,
 * plain http only to a private host). A refused URL is never opened: the state is [State.URL_REFUSED]
 * until a Setup save, and the log line names only the policy's reason, never the URL or token.
 *
 * DEV-544, the refresh-token login: when [HaConnectionConfig.login] is non-null (the phone was enrolled by a one-time code),
 * every connect first gets an access token from it: the in-memory one when it has 5 minutes or more left, else a refresh at
 * HA's `/auth/token` on [blocking] (never this class's own thread). `invalid_grant` is [State.SIGNED_OUT]: terminal, no retry,
 * until a Setup save. Any other refresh failure retries on the ordinary [Backoff]. `auth_invalid` on such a connect refreshes
 * and reconnects ONCE; a second one is [State.AUTH_REJECTED]. A phone with no `login` (the old long-lived token) takes the
 * code path that was here before, unchanged.
 *
 * DEV-557: the app passes a `FallbackConnector` (`:core`): `ha_url` directly first, the tailnet
 * second. This class does not know which path a socket took. Answering
 * rides this socket through [PorticoLink.sendCall] and [PorticoLink.openStream]; this class adds no
 * keepalive of its own.
 */
class SignalingClient(
    private val config: HaConnectionConfig,
    private val link: PorticoLink,
    private val connector: Connector = OkHttpConnector(),
    private val backoff: Backoff = Backoff(),
    private val log: HaLog = HaLog.NONE,
    private val tickMs: Long = 5_000,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "portico-signaling").apply { isDaemon = true }
    },
    /** Monotonic milliseconds, for [notConnectedForMs]. The app passes `SystemClock.elapsedRealtime`. */
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /** HA refused the token (Setup's per-path line says so on the path that opened). */
    private val onAuthRejected: () -> Unit = {},
    /**
     * Pause (Setup's "Pause this phone"): while true nothing opens a socket: [start], [wake],
     * [reconnect] and the retry timer all leave the client [State.PAUSED] (not running, no ticker,
     * no link start, so no FID request either). [pause] closes an open one. FAIL OPEN: a flag that
     * cannot be read is "not paused". The resume is simply [start], as at process start.
     */
    private val paused: () -> Boolean = { false },
    /** DEV-544: where the refresh request runs (it blocks on the network). The app's default is one daemon thread. */
    private val blocking: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "portico-token-refresh").apply { isDaemon = true } },
    /** DEV-544: HA no longer knows the login (`invalid_grant`). Runs on the signaling thread. */
    private val onSignedOut: () -> Unit = {},
    /**
     * DEV-705, the connection log: a step of the connect as a fixed word, with an optional class word and number. Events:
     * `state` (cls = the new state), `socket_connect`, `socket_open`, `closed` (cls = the reason as a word), `retry` (n = the
     * wait in ms), `wake`, `reconnect_requested`. Observation only: guarded, runs on the signaling thread.
     */
    private val onTrace: (event: String, cls: String?, n: Long?) -> Unit = { _, _, _ -> },
    /** The link is in use right now (a ring is up, or the screen is on): the watchdog asks every ~10 s instead of ~30 s. Fail-quiet: a throw is "not active". */
    private val active: () -> Boolean = { false },
    /**
     * DEV-811, the link race: true on a mobile or tailnet link (never on Wi-Fi, where the strict 2 s rule never misfires). When the watchdog's
     * strict deadline passes on such a link the working socket is NOT dropped: a second one is opened beside it and whichever is live first
     * wins. Fail-quiet: a throw is false, the strict rule of before.
     */
    private val raceOnLink: () -> Boolean = { false },
    /** DEV-811: a candidate socket that is not live (subscribed) within this long is closed. */
    private val candidateTimeoutMs: Long = CANDIDATE_TIMEOUT_MS,
    /** DEV-811: the path word for the race's journal lines (`direct` / `tailnet`), or null. */
    private val pathWord: () -> String? = { null },
    /** DEV-811: after a race the OLD link won, the next candidate waits this long (a link whose pongs take over 2 s would otherwise race every few seconds). */
    private val raceGapMs: Long = RACE_GAP_MS,
) {
    /** [PAUSED]: the phone is paused (Setup); closed and not retrying, on purpose, until started again. */
    enum class State { STOPPED, NOT_CONFIGURED, CONNECTING, CONNECTED, WAITING_TO_RETRY, AUTH_REJECTED, URL_REFUSED, PAUSED, SIGNED_OUT }

    /** Offline registration: how long the socket has gone without being [State.CONNECTED] (authenticated). */
    private val notConnected = NotConnectedTimer(clockMs())

    /** The socket has been [State.CONNECTED] at least once in this process: a later connect is a RE-connect (the screens say so). */
    @Volatile
    var everConnected: Boolean = false
        private set

    @Volatile
    var state: State = State.STOPPED
        private set(value) {
            val old = field
            field = value
            if (value == State.CONNECTED) everConnected = true
            notConnected.update(value == State.CONNECTED, clockMs())
            if (old != value) trace("state", value.name.lowercase())
        }

    private fun trace(event: String, cls: String? = null, n: Long? = null) {
        try {
            onTrace(event, cls, n)
        } catch (_: Exception) {
            // the connection log must never disturb a connect
        }
    }


    /**
     * Milliseconds since the socket was last [State.CONNECTED] (0 while it is), counted from
     * creation if it never was. Retries within one outage do not restart it. For Setup's
     * "this phone cannot reach Home Assistant" block (`:core` `OfflineRegistration`).
     */
    fun notConnectedForMs(): Long = notConnected.notConnectedForMs(clockMs())

    /** Connection attempts since [start] (for tests and the status view). */
    @Volatile
    var attempts: Int = 0
        private set

    private var running = false
    private var generation = 0
    private var transport: WsTransport? = null
    private var retry: ScheduledFuture<*>? = null
    private var ticker: ScheduledFuture<*>? = null
    private var watchdogTicker: ScheduledFuture<*>? = null

    // DEV-811, the link race (executor thread only). At most ONE candidate: its transport, its callback id, its give-up timer.
    private var candT: WsTransport? = null
    private var candId = -1
    private var candSeq = 0
    private var candTimer: ScheduledFuture<*>? = null
    private var raceStartedMs = 0L

    /** When the old link last answered a race (the candidate was not needed); -1 = never. A new candidate waits [raceGapMs] after it. */
    private var lastWonByOldMs = -1L

    /** The id of the candidate that won: its callbacks are now the working socket's. */
    private var adoptedId = -1

    /** DEV-544: the one automatic refresh-and-reconnect after an `auth_invalid` on a login connect has been used. */
    private var authRetried = false

    fun start() = executor.execute { startOnThread() }

    /**
     * PR-E: a push rang, so HA should confirm the call now (spec §4.3, "the push is a wake hint").
     * Starts the client if it is not running; connects at once (backoff reset) if it is waiting
     * to retry or was not configured; leaves a connecting or connected socket alone, and does
     * not retry a token HA refused (AUTH_REJECTED waits for Setup).
     */
    fun wake() = executor.execute {
        if (isPaused()) {
            enterPaused()
            return@execute
        }
        trace("wake")
        if (!running) {
            startOnThread()
            return@execute
        }
        when (state) {
            State.WAITING_TO_RETRY, State.NOT_CONFIGURED -> {
                backoff.reset()
                connectNow()
            }
            else -> Unit
        }
    }

    private fun startOnThread() {
        if (isPaused()) {
            enterPaused()
            return
        }
        if (running) return
        running = true
        link.start()
        ticker = executor.scheduleWithFixedDelay({ guarded { link.tick() } }, tickMs, tickMs, TimeUnit.MILLISECONDS)
        watchdogTicker = executor.scheduleWithFixedDelay({ guarded { watchdogStep() } }, LinkWatchdog.TICK_MS, LinkWatchdog.TICK_MS, TimeUnit.MILLISECONDS)
        connectNow()
    }

    /**
     * DEV-764 aftermath: one liveness step ([PorticoLink.watchdogStep]). A link that went quiet and did not answer HA's `ping` in time
     * is dropped and reconnected AT ONCE (backoff reset): a tunnel that stalled while the socket still said "connected" used to hold
     * a ring's Answer for the 30+ s socket timeout. Only a connected socket is watched; a connect in progress has its own deadlines.
     */
    private fun watchdogStep() {
        if (!running || state != State.CONNECTED || transport == null) return
        val busy = try {
            active()
        } catch (_: Exception) {
            false
        }
        val candidate = candT != null
        // a candidate may be opened unless one exists, media rides this socket, or the old link just answered a race (min gap)
        // The gap after a race the old link won bounds a candidate storm; it withholds only the 2 s trigger. Once the ping is 4 s old a
        // candidate is still started, so a link that really stalls is never found at 6 s with nothing prepared.
        val gapOver = lastWonByOldMs < 0 || clockMs() - lastWonByOldMs >= raceGapMs
        val mayRace = !candidate && !link.hasOpenStreams() && (gapOver || link.pingAgeMs() >= LATE_RACE_MS)
        when (link.watchdogVerdict(busy, ::raceAllowed, mayRace, mobileKnown = candidate)) {
            LinkWatchdog.Verdict.RACE -> startRace()
            LinkWatchdog.Verdict.DEAD -> {
                trace("watchdog", "dead", attempts.toLong())
                if (candT != null) {
                    // the old link is dead for good and its replacement is already on the way: keep it, it is today's reconnect, started earlier
                    log.log(Level.WARN, "link watchdog: no sign of life from Home Assistant; the second connection carries on")
                    dropSocket("link watchdog", keepCandidate = true)
                    state = State.CONNECTING
                    return
                }
                log.log(Level.WARN, "link watchdog: no sign of life from Home Assistant; reconnecting")
                dropSocket("link watchdog")
                backoff.reset()
                connectNow()
            }
            else -> Unit
        }
    }

    /** Asked only when the strict deadline has passed (never on a tick that decides nothing): is this a mobile or tailnet link, with the race allowed. */
    private fun raceAllowed(): Boolean = try {
        raceOnLink()
    } catch (_: Exception) {
        false
    }

    private fun pathWordNow(): String? = try {
        pathWord()
    } catch (_: Exception) {
        null
    }

    /**
     * DEV-811: the strict deadline passed on a mobile or tailnet link. Open a SECOND socket (connect, auth, version, doors, calls and content
     * subscriptions, as a normal start does) beside the working one. Nothing is delivered from it and nothing is sent on it until it wins; while
     * it exists the working link takes no command but a hangup (the answer path waits and sends once on the winner). If NO candidate can be
     * opened (no cached token, a refused URL, the connector throws) the link is dropped and reconnected at once, as before the race existed:
     * the race must never make a dead link slower to replace.
     */
    private fun startRace() {
        if (candT != null || !running || state != State.CONNECTED || transport == null || isPaused()) return
        val haUrl = config.haUrl
        val login = config.login
        val token = if (login != null) login.cached() else config.accessToken
        val url = HaUrls.websocketUrl(haUrl)
        val why = when {
            haUrl.isNullOrBlank() || url == null || UrlPolicy.check(haUrl) is UrlCheck.Refused -> "url"
            token.isNullOrBlank() -> "token" // a refresh would block; connectNow does it
            else -> null
        }
        if (why != null) return raceSkipped(why)
        val id = ++candSeq
        val t = try {
            connector.openCandidate(url!!, object : WsListener {
                override fun onOpen() = Unit
                override fun onText(text: String) = executor.execute {
                    if (id == candId) handleCandidateText(text) else if (id == adoptedId) handleText(text)
                }
                override fun onClosed(reason: String) = executor.execute {
                    if (id == candId) handleCandidateClosed() else if (id == adoptedId) handleClosed(reason)
                }
            })
        } catch (e: Exception) {
            log.log(Level.WARN, "second connection failed: ${e.javaClass.simpleName}")
            return raceSkipped("open")
        }
        candId = id
        raceStartedMs = clockMs()
        candT = t
        val (fast, silent) = link.raceFacts()
        link.raceNote("start", "fast" to fast, "silent" to silent, "path" to pathWordNow())
        trace("race", "start")
        guarded { link.openCandidate(t, token!!) }
        candTimer = executor.schedule({ if (id == candId) giveUpCandidate("timeout") }, candidateTimeoutMs, TimeUnit.MILLISECONDS)
    }

    /** No candidate could be made: say why and do what the strict rule did before the race existed. */
    private fun raceSkipped(why: String) {
        link.raceNote("skip", "why" to why)
        trace("race", "skip")
        log.log(Level.WARN, "link watchdog: no second connection possible ($why); reconnecting")
        dropSocket("link watchdog")
        backoff.reset()
        connectNow()
    }

    private fun handleCandidateText(text: String) {
        guarded { link.onCandidateText(text) }
        if (link.candidateFailed) {
            giveUpCandidate("refused")
        } else if (link.candidateReady) {
            swapToCandidate()
        }
    }

    private fun handleCandidateClosed() {
        candT = null // already closed by the peer
        giveUpCandidate("closed")
    }

    private fun giveUpCandidate(why: String) {
        link.raceNote("gaveup", "why" to why, "ms" to clockMs() - raceStartedMs)
        trace("race", "gaveup", clockMs() - raceStartedMs)
        closeCandidate("second connection $why")
        candidateEnded()
    }

    /**
     * The candidate is gone. If the old socket was already dropped (it was dead) nothing carries the link now: connect again at once, as the
     * watchdog's plain DEAD does (backoff reset), not after a retry step.
     */
    private fun candidateEnded() {
        if (running && transport == null && retry == null && state == State.CONNECTING) {
            backoff.reset()
            connectNow()
        }
    }

    /** The candidate is subscribed first: it becomes the link and the old socket (if still open) is closed; a command in flight on it is Lost. */
    private fun swapToCandidate() {
        val newT = candT ?: return
        val oldT = transport
        val ms = clockMs() - raceStartedMs
        generation++ // late callbacks from the old socket are ignored
        adoptedId = candId
        candId = -1
        candTimer?.cancel(false)
        candTimer = null
        candT = null
        retry?.cancel(false)
        retry = null
        transport = newT
        var swapped = false
        guarded { swapped = link.promoteCandidate() }
        try {
            oldT?.close(1000, "link race swap")
        } catch (_: Exception) {
        }
        if (!swapped) {
            // cannot happen after candidateReady; if it does, start over the plain way
            transport = null
            adoptedId = -1
            try {
                newT.close(1000, "link race failed")
            } catch (_: Exception) {
            }
            state = State.CONNECTING
            scheduleRetry()
            return
        }
        try {
            newT.promote()
        } catch (e: Exception) {
            log.log(Level.WARN, "second connection: promote failed (${e.javaClass.simpleName})")
        }
        link.raceNote("won", "by" to "new", "ms" to ms, "old" to (if (oldT != null) "open" else "dropped"), "path" to pathWordNow())
        trace("race", "won_new", ms)
        state = State.CONNECTED
        backoff.markConnected()
        authRetried = false
        guarded { if (link.fastWatching()) ensureFastTicker() } // the ring / Answer window came with the swap
    }

    /** The working socket was heard from while a candidate was being built: it was alive, the candidate is not needed. */
    private fun oldAnswered() {
        val ms = clockMs() - raceStartedMs
        lastWonByOldMs = clockMs()
        link.raceNote("won", "by" to "old", "ms" to ms, "path" to pathWordNow())
        trace("race", "won_old", ms)
        closeCandidate("the first socket answered")
    }

    /** [cancelWord] (a fixed word) writes `link-race-cancel` when a candidate existed: every candidate gets exactly one closing line. */
    private fun closeCandidate(reason: String, cancelWord: String? = null) {
        if (cancelWord != null && candT != null) link.raceNote("cancel", "why" to cancelWord, "ms" to clockMs() - raceStartedMs)
        candTimer?.cancel(false)
        candTimer = null
        candId = -1
        val t = candT
        candT = null
        link.dropCandidate()
        try {
            t?.close(1000, reason)
        } catch (_: Exception) {
        }
    }

    private var fastTicker: ScheduledFuture<*>? = null

    private fun ensureFastTicker() {
        if (fastTicker?.isDone == false) return
        // the 2 s step is too coarse for a 2 s deadline; this one runs only while the fast rule is on, then ends itself
        var t: ScheduledFuture<*>? = null
        t = executor.scheduleWithFixedDelay({
            guarded { watchdogStep() }
            if (!link.fastWatching()) t?.cancel(false)
        }, 0, LinkWatchdog.FAST_TICK_MS, TimeUnit.MILLISECONDS)
        fastTicker = t
    }

    /**
     * the owner, 2026-11-18: after an Answer press the link is checked every 2 s for 15 s, one unanswered ping drops it ([LinkWatchdog]).
     */
    fun fastWatch() = executor.execute {
        if (!running) return@execute
        link.answerFastWatch()
        ensureFastTicker()
    }

    /**
     * the owner, 2026-11-18: "the pattern of pinging HA and establishing the connection has to start as soon as ringing starts". A door ring
     * was decided: a link that is down is brought back NOW (backoff reset, [wake]'s rule; the path hint decides tailnet-first), a link that is
     * up and quiet is pinged at once, and the fast rule runs until the ring ends.
     */
    fun ringStarted() {
        wake()
        executor.execute {
            if (!running) return@execute
            link.ringFastWatch()
            trace("ring_watch", state.name.lowercase(), null)
            ensureFastTicker()
        }
    }

    /** The ring ended (answered, declined, cancelled, timed out). */
    fun ringEnded() = executor.execute { link.ringEndedWatch() }

    /** Closes the socket and stops reconnecting. */
    fun stop() = executor.execute {
        running = false
        ticker?.cancel(false)
        watchdogTicker?.cancel(false)
        fastTicker?.cancel(false)
        dropSocket("stopped")
        state = State.STOPPED
    }

    /** Pause: close the socket and stop reconnecting, in state [State.PAUSED]. */
    fun pause() = executor.execute { enterPaused() }

    /** After a Setup save (new URL or token): drop any socket and connect at once. */
    fun reconnect() = executor.execute {
        if (!running) {
            trace("reconnect_ignored", "not_running") // DEV-705: a network change that found the client stopped
            return@execute
        }
        if (isPaused()) {
            enterPaused()
            return@execute
        }
        trace("reconnect_requested") // after the guards: a paused or stopped client opens no sequence nothing would close
        backoff.reset()
        authRetried = false
        dropSocket("reconnect requested")
        connectNow()
    }

    /** Runs [block] on the link's thread: rotation, push switch, sign-out, status requests. */
    fun post(block: (PorticoLink) -> Unit) = executor.execute { guarded { block(link) } }

    /** Stops and releases the thread. */
    fun shutdown() {
        stop()
        executor.shutdown()
    }

    override fun toString(): String = "SignalingClient(state=$state, attempts=$attempts, $link)"

    // ---- internals (executor thread only) --------------------------------------------------

    private fun connectNow() {
        closeCandidate("connecting", "connect")
        retry?.cancel(false)
        retry = null
        if (!running) return
        if (isPaused()) {
            enterPaused()
            return
        }
        val haUrl = config.haUrl
        val login = config.login
        if (login != null) {
            connectWithLogin(haUrl, login)
            return
        }
        val token = config.accessToken
        if (haUrl.isNullOrBlank() || token.isNullOrBlank()) {
            state = State.NOT_CONFIGURED
            log.log(Level.INFO, "not connecting: no ha_url or token yet")
            return
        }
        openSocket(haUrl, token)
    }

    /**
     * DEV-544: a connect for a phone with a refresh-token login. A fresh enough access token opens the socket at once; otherwise
     * the refresh runs on [blocking] and the result comes back to this thread, where a connect that was dropped, paused or
     * replaced meanwhile (the generation moved) is ignored.
     */
    private fun connectWithLogin(haUrl: String?, login: AccessTokens) {
        if (haUrl.isNullOrBlank()) {
            state = State.NOT_CONFIGURED
            log.log(Level.INFO, "not connecting: no ha_url yet")
            return
        }
        val cached = login.cached()
        if (cached != null) {
            openSocket(haUrl, cached)
            return
        }
        state = State.CONNECTING
        val epoch = ++generation
        try {
            blocking.execute {
                val r = try {
                    login.obtain()
                } catch (e: Exception) {
                    AccessTokens.Result.Failed(0)
                }
                executor.execute { onTokenResult(epoch, r) }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            scheduleRetry()
        }
    }

    private fun onTokenResult(epoch: Int, r: AccessTokens.Result) {
        if (epoch != generation || !running) return // dropped, replaced or stopped while the refresh was out
        if (isPaused()) {
            enterPaused()
            return
        }
        when (r) {
            is AccessTokens.Result.Token -> {
                val url = config.haUrl
                if (url.isNullOrBlank()) {
                    state = State.NOT_CONFIGURED
                    return
                }
                openSocket(url, r.value)
            }
            AccessTokens.Result.SignedOut -> {
                state = State.SIGNED_OUT
                runCatching(onSignedOut)
                log.log(Level.WARN, "HA no longer knows this phone's login; not reconnecting until it is enrolled again")
            }
            AccessTokens.Result.Unreachable, is AccessTokens.Result.Failed -> {
                log.log(Level.INFO, "could not refresh the access token; retrying")
                scheduleRetry()
            }
        }
    }

    private fun openSocket(haUrl: String, token: String) {
        // PR-J: Android permits cleartext for the whole app; this check is the real restriction.
        val check = UrlPolicy.check(haUrl)
        val url = HaUrls.websocketUrl(haUrl)
        if (check is UrlCheck.Refused || url == null) {
            state = State.URL_REFUSED
            val why = (check as? UrlCheck.Refused)?.reason?.name ?: "NO_WEBSOCKET_URL"
            log.log(Level.WARN, "not connecting: the stored ha_url is refused by the network policy ($why)")
            return
        }
        val gen = ++generation
        attempts++
        trace("socket_connect", null, attempts.toLong())
        state = State.CONNECTING
        val t = try {
            connector.open(url, object : WsListener {
                override fun onOpen() = executor.execute {
                    if (gen == generation) {
                        log.log(Level.DEBUG, "socket open")
                        trace("socket_open")
                    }
                }
                override fun onText(text: String) = executor.execute { if (gen == generation) handleText(text) }
                override fun onClosed(reason: String) = executor.execute { if (gen == generation) handleClosed(reason) }
            })
        } catch (e: Exception) {
            log.log(Level.WARN, "connect failed: ${e.javaClass.simpleName}")
            scheduleRetry()
            return
        }
        transport = t
        link.onSocketOpen(t, token)
    }

    private fun handleText(text: String) {
        if (candT != null) oldAnswered()
        guarded { link.onSocketText(text) }
        if (link.contractMismatch != null) {
            // R7-05: the server answered another contract. The link has asked the transport to close and told its listener;
            // this does not wait for the server's close frame (a half-open socket would hold the retry for a minute): the
            // socket is dropped now, the mismatch cleared with it, and the backoff loop asks `portico/version` again.
            // Never CONNECTED, never AUTH_REJECTED.
            log.log(Level.WARN, "the server runs another Portico contract; retrying")
            dropSocket("contract mismatch")
            scheduleRetry()
            return
        }
        if (link.isReady && state != State.CONNECTED) {
            state = State.CONNECTED
            backoff.markConnected()
            authRetried = false
        }
    }

    private fun handleClosed(reason: String) {
        trace("closed", reason) // raw: the connection log whitelists it (core ReasonWords)
        transport = null
        guarded { link.onSocketClosed(reason) }
        val login = config.login
        if (link.authRejected && login != null && !authRetried) {
            // DEV-544: HA refused a token that looked fresh (the login was revoked, or the clock lied). Refresh and reconnect
            // once; the refresh answers `invalid_grant` if the login is really gone.
            authRetried = true
            login.invalidate()
            log.log(Level.INFO, "HA refused the access token; refreshing once")
            connectNow()
            return
        }
        if (link.authRejected) {
            closeCandidate("auth rejected", "auth")
            state = State.AUTH_REJECTED
            runCatching(onAuthRejected)
            log.log(Level.WARN, "HA refused the token; not reconnecting until Setup changes")
            return
        }
        if (candT != null) {
            // the working socket closed while its replacement is on the way: that one is the reconnect, no second one is started
            state = State.CONNECTING
            return
        }
        scheduleRetry()
    }

    private fun scheduleRetry() {
        if (!running) return
        val delay = backoff.nextDelayMs()
        trace("retry", null, delay)
        state = State.WAITING_TO_RETRY
        log.log(Level.INFO, "reconnecting in $delay ms")
        retry = executor.schedule({ connectNow() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun isPaused(): Boolean = try {
        paused()
    } catch (_: Exception) {
        false // fail open: an unreadable flag must never keep the doorbell deaf
    }

    /** Not running, no ticker, no retry, no socket. Idempotent. */
    private fun enterPaused() {
        running = false
        ticker?.cancel(false)
        ticker = null
        watchdogTicker?.cancel(false)
        watchdogTicker = null
        fastTicker?.cancel(false)
        fastTicker = null
        closeCandidate("paused", "pause")
        if (transport != null || retry != null) dropSocket("paused")
        if (state != State.PAUSED) log.log(Level.INFO, "paused: not connecting to Home Assistant until resumed")
        state = State.PAUSED
    }

    private fun dropSocket(reason: String, keepCandidate: Boolean = false) {
        if (!keepCandidate) closeCandidate(reason, "drop")
        retry?.cancel(false)
        retry = null
        adoptedId = -1
        generation++ // late callbacks from the old socket are ignored
        transport?.close(1000, reason)
        transport = null
        guarded { link.onSocketClosed(reason) }
    }

    companion object {
        /** DEV-811: a candidate socket that is not live within this long is closed. */
        const val CANDIDATE_TIMEOUT_MS = 15_000L

        /** DEV-811: minimum time between a race the old link won and the next candidate. */
        const val RACE_GAP_MS = 20_000L

        /** DEV-811: inside the gap a candidate is started once the unanswered ping is this old (2 s before the 6 s deadline). */
        const val LATE_RACE_MS = 4_000L
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.log(Level.ERROR, "signaling: ${e.javaClass.simpleName} in the link")
        }
    }
}
