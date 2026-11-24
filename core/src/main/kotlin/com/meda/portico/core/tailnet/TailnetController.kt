package com.meda.portico.core.tailnet

import com.meda.portico.core.ha.ConnectTimeline
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.HaLog.Level

/**
 * The bridge to `gocore/tailnet` (DEV-557). The app implements it over `gocore.aar`
 * (`GoTailnetEngine`); an APK built without the library gets [NOT_BUILT].
 *
 * ⚠️ Every call may cross into Go and may block briefly (the first one waits for the native
 * library to load). NEVER call it from the UI thread: memory `gomobile-load-must-be-off-main-thread`.
 */
interface TailnetEngine {
    /** False only for [NOT_BUILT]. */
    val built: Boolean

    /**
     * The native library is loaded or loading. The app loads it lazily (30 MB of Go runtime), on
     * the first [ready]; until then [TailnetController.refresh] answers from files alone and never
     * wakes it (a push-woken process at home must not pay for it).
     */
    val started: Boolean get() = built

    /** Null once the library is loaded and prepared, else why not (waits a bounded time for the load). */
    fun ready(): String?

    /**
     * Starts the load without waiting for it (idempotent). The controller never calls [ready]
     * while holding its lock: it reads [loaded] instead, which never blocks.
     */
    fun beginLoad() {}

    /** The load has finished, successfully or not ([ready] then answers at once). Never blocks. Default: nothing to wait for. */
    val loaded: Boolean get() = true

    /** Starts a join (returns at once). [authKey] null: come back as the stored identity. Throws if refused. */
    fun join(authKey: String?, hostname: String)

    /** The Go side's one-line status ([GoStatus.parse]). */
    fun status(): String

    /** Opens (or reuses) the local forwarder to [target]; returns `"<port>:<token hex>"`. Throws if not up. */
    fun startForwarder(target: String): String

    /** Stops the node and the forwarder; the stored identity stays. */
    fun stop()

    /** `gocore/tailnet.RouteCheck` ([RouteCheck.parse]). Defaults to `unknown` (an older library). */
    fun routeCheck(target: String): String = "unknown"

    /** `gocore/tailnet.ForwarderStatus` ([ForwarderDial.parse]). Defaults to `none`. */
    fun forwarderStatus(): String = "none"

    /**
     * Blocks up to [timeoutMs] or until the Go side's status line changes, whichever is first (DEV-694): the
     * controller used to look every 250 ms, so a node that came up was noticed up to 250 ms late. True: the
     * wait was done here (the caller must NOT also sleep). False (the default): not supported, the caller sleeps.
     * Worker threads only; never under a lock.
     */
    fun awaitStatusChange(timeoutMs: Long): Boolean = false

    /** The latest join's timeline from tsnet's own IPN bus ("starting+30ms running+285ms"); empty when unknown. */
    fun joinPhases(): String = ""

    /** This phone has a stored tailnet identity (it joined before). */
    fun hasIdentity(): Boolean

    /** Deletes the stored identity (before redeeming a NEW key: a new key means a new node). */
    fun wipeIdentity()

    companion object {
        val NOT_BUILT: TailnetEngine = object : TailnetEngine {
            override val built = false
            override fun ready() = "the tailnet client is not built into this APK"
            override fun join(authKey: String?, hostname: String) = throw IllegalStateException("not built")
            override fun status() = "idle"
            override fun startForwarder(target: String) = throw IllegalStateException("not built")
            override fun stop() = Unit
            override fun hasIdentity() = false
            override fun wipeIdentity() = Unit
        }
    }
}

/** The enrollment's single-use Tailscale key while it waits to be redeemed. [toString] never shows it. */
class PendingKey(val key: String, val savedAtMs: Long) {
    override fun toString(): String = "PendingKey(present, savedAtMs=$savedAtMs)"
}

/** Where the pending key lives (the app: the encrypted Settings, written by the enrollment save). */
interface PendingKeyStore {
    fun load(): PendingKey?
    fun clear()
}

/** The forwarder the tailnet connector points its sockets at. [toString] never shows the token. */
class Forwarder(val port: Int, private val token: ByteArray) {
    /** The 32 bytes every connection must send first. A copy. */
    fun token(): ByteArray = token.copyOf()

    /** Where the connector's sockets go: `127.0.0.1:<port>`. Not secret. */
    fun endpoint(): String = endpointOf(port)

    override fun toString(): String = "Forwarder(${endpoint()}, token=present)"

    companion object {
        const val TOKEN_BYTES = 32

        /**
         * The forwarder's address: IPv4 loopback, exactly what gocore/tailnet/forwarder.go binds.
         * Never "the platform's loopback": on Android `InetAddress.getLoopbackAddress()` is `::1`,
         * where nothing listens (the 2026-10-08 "forwarder was not listening" failure).
         */
        const val HOST = "127.0.0.1"

        /** [HOST] as address bytes, for `InetAddress.getByAddress` (no resolver involved). A copy. */
        fun hostBytes(): ByteArray = byteArrayOf(127, 0, 0, 1)

        fun endpointOf(port: Int): String = "$HOST:$port"

        /** Parses `"<port>:<64 hex chars>"`; null for anything else. */
        fun parse(s: String?): Forwarder? {
            val (p, hex) = (s ?: return null).trim().split(':').takeIf { it.size == 2 } ?: return null
            val port = p.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            if (hex.length != TOKEN_BYTES * 2) return null
            val b = ByteArray(TOKEN_BYTES)
            for (i in 0 until TOKEN_BYTES) {
                val v = hex.substring(2 * i, 2 * i + 2).toIntOrNull(16) ?: return null
                b[i] = v.toByte()
            }
            return Forwarder(port, b)
        }
    }
}

/** The tailnet node's name: `portico-<phone name>`, DNS-label safe. */
object TailnetHostname {
    fun of(phoneName: String?): String {
        val base = (phoneName ?: "").lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
        val name = if (base.isEmpty()) "portico-phone" else "portico-$base"
        return name.take(63).trimEnd('-')
    }
}

/**
 * The tailnet client's state machine and the key lifecycle (DEV-557, docs/tailnet.md). Pure:
 * the engine, the key store and the clocks are injected, so every branch is JVM-tested.
 *
 * Key lifecycle:
 * - The enrollment save stores the QR's key with its save time ([PendingKeyStore]), and
 *   [onEnrollmentSaved] starts redeeming it at once (a NEW key means a new node: the old identity
 *   is wiped first). An enrollment without a key changes nothing here.
 * - The key is deleted the moment the node is up (it is single-use and spent), when the tailnet
 *   refuses it, or once it is older than [MAX_KEY_AGE_MS] ([TailnetState.KeyExpired]). Until then
 *   a failed join (no internet, timeout) is retried on the next connect.
 * - After the first join the node key in the app-private state dir authenticates; no key is kept.
 *
 * Path rule: the tailnet is woken only when the direct URL fails ([awaitForwarder], called by
 * the tailnet connector) and put to sleep again when the direct URL works ([sleep]).
 *
 * Thread-safety: every method is safe from any worker thread; none may run on the UI thread.
 * [state] is a cached value and is safe anywhere.
 */
class TailnetController(
    private val engine: TailnetEngine,
    private val keys: PendingKeyStore,
    private val hostname: () -> String,
    private val target: () -> String?,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val log: HaLog = HaLog.NONE,
    /**
     * The phone is paused (Setup's "Pause this phone"): the node sleeps and nothing wakes it.
     * [ensureUp] (and so [awaitForwarder]) and [onEnrollmentSaved] start no join. FAIL OPEN: a
     * flag that cannot be read is "not paused".
     */
    private val paused: () -> Boolean = { false },
    /** DEV-694 P0: where the time of a connect goes. Marks are written only from [awaitForwarder]'s path, never from a Setup refresh. */
    private val timeline: ConnectTimeline = ConnectTimeline(),
    /**
     * DEV-694 (the owner, 2026-10-23): when the direct path wins and the node is UP (it carried a
     * connect a moment ago), keep it warm this long before stopping it, so a reconnect within the
     * window (Wi-Fi flapping at the edge of the house, a quick step out and back) skips the 1.7 s
     * rejoin. 0 (the default) stops at once, as before. A node still joining is a speculative
     * start nobody wanted: that one is stopped at once either way.
     */
    private val holdMs: Long = 0L,
    /** Schedules a task after a delay and returns its cancel action (the app: its tailnet executor). */
    private val holdTimer: (Long, () -> Unit) -> (() -> Unit) = { _, _ -> {} },
) {
    private fun isPaused(): Boolean = try {
        paused()
    } catch (_: Exception) {
        false
    }

    /**
     * Setup's route line: whether a peer routes to the dial target's IP. Refreshed with [state]
     * while the node is up and the target is an IP; [RouteCheck.Unknown] otherwise. UI-safe.
     */
    @Volatile
    var route: RouteCheck = RouteCheck.Unknown
        private set

    /**
     * The forwarder's last dial (for the tailnet path's failure class). Asks Go only when the
     * library is loaded; never loads it. Worker threads only.
     */
    fun forwarderDial(): ForwarderDial? {
        if (!engine.built || !engine.started) return null
        return try {
            ForwarderDial.parse(engine.forwarderStatus())
        } catch (e: Exception) {
            null
        }
    }

    /** The last refreshed state. Cheap to read from any thread (Setup's UI reads it). */
    @Volatile
    var state: TailnetState = if (engine.built) TailnetState.Idle(hasIdentity = false) else TailnetState.NotBuilt
        private set

    private val lock = Any()

    /** A thread is waiting for the library (outside [lock]) and will then join: nobody else starts one. */
    private var joinPending = false
    private var keyExpired = false
    private var joinUsedKey = false

    /** The stored identity was refused (NEEDS_LOGIN without a key): do not retry it until a new enrollment. */
    private var identityDead = false

    /** Re-reads the Go side, applies the key rules, and returns the new [state]. */
    fun refresh(): TailnetState = synchronized(lock) { refreshLocked() }

    /** After a Setup save. With a pending key: forget any old node and redeem the key now. */
    fun onEnrollmentSaved(): TailnetState {
        synchronized(lock) {
            if (!engine.built) return state
            val pk = keys.load() ?: return refreshLocked()
            if (isPaused()) {
                // The key waits (it still expires on its own clock); the resume's first connect redeems it.
                log.log(Level.INFO, "tailnet: paused; the enrollment's key waits for the resume")
                return refreshLocked()
            }
            keyExpired = false
            identityDead = false
            if (expiredLocked(pk)) return refreshLocked()
            joinPending = true
        }
        // The library load can take up to 30 s: wait for it OUTSIDE the lock.
        val err = try {
            engine.ready()
        } catch (t: Throwable) {
            synchronized(lock) { joinPending = false }
            throw t
        }
        return synchronized(lock) {
            joinPending = false
            err?.let { return setState(TailnetState.Failed(FailReason.LIBRARY, it)) }
            cancelHoldLocked()
            // Things moved while we waited: paused, or the key was spent/replaced. Use what is true now.
            if (isPaused()) return refreshLocked()
            val pk = keys.load() ?: return refreshLocked()
            engine.stop()
            engine.wipeIdentity()
            joinLocked(pk.key)
            refreshLocked()
        }
    }

    /**
     * Brings the node up if it can be: a pending key is (re)tried until it expires, else a stored
     * identity rejoins without a key. Returns at once; [awaitForwarder] waits.
     */
    fun ensureUp(cancelled: () -> Boolean = { false }): TailnetState {
        synchronized(lock) {
            val s = refreshLocked()
            if (isPaused()) return s
            if (s is TailnetState.Up || s is TailnetState.Joining || s is TailnetState.NotBuilt) return s
            if (s is TailnetState.Failed && s.reason == FailReason.LIBRARY) return s
            if (cancelled()) return s // the direct path already won: do not even start loading 30 MB of Go for nobody
            if (joinPending) return setState(TailnetState.Joining()) // another thread is waiting for the library
            if (keys.load() == null && !(engine.hasIdentity() && !identityDead)) return s
            joinPending = true
        }
        // The library load can take up to 30 s: wait for it OUTSIDE the lock, so a Setup refresh,
        // sleep() or pause() is never stuck behind it.
        val err = try {
            engine.ready()
        } catch (t: Throwable) {
            synchronized(lock) { joinPending = false }
            throw t
        }
        return synchronized(lock) {
            joinPending = false
            err?.let { return setState(TailnetState.Failed(FailReason.LIBRARY, it)) }
            mark(ConnectTimeline.Stage.LIBRARY_READY)
            // The attempt that asked for the node was abandoned while the library loaded (the direct
            // path won): do not join for nobody.
            if (isPaused() || cancelled()) return refreshLocked()
            val now = refreshLocked()
            if (now is TailnetState.Up || now is TailnetState.Joining) return now // someone else got there first
            // Decide the key again: the enrollment may have spent or replaced it while we waited.
            val pk = keys.load()
            when {
                pk != null -> joinLocked(pk.key)
                engine.hasIdentity() && !identityDead -> joinLocked(null)
                else -> return now
            }
            refreshLocked()
        }
    }

    /**
     * For the tailnet connector (on its worker thread): wakes the node, waits up to [timeoutMs]
     * for it, and returns the forwarder to [target]; null when it did not come up (see [state]) or
     * when [cancelled] turned true (checked every poll: an abandoned attempt gives the caller's
     * worker back at once; the join itself carries on).
     */
    fun awaitForwarder(timeoutMs: Long, cancelled: () -> Boolean = { false }): Forwarder? {
        val t = target()
        if (t == null) {
            synchronized(lock) { setState(TailnetState.Failed(FailReason.NO_TARGET)) }
            return null
        }
        val deadline = wallClockMs() + timeoutMs
        var s = ensureUp(cancelled)
        while (true) {
            if (cancelled()) return null
            when (s) {
                is TailnetState.Up -> return synchronized(lock) {
                    mark(ConnectTimeline.Stage.NODE_UP)
                    try {
                        Forwarder.parse(engine.startForwarder(t))?.also {
                            mark(ConnectTimeline.Stage.FORWARDER_READY)
                            noteJoinPhases()
                        }
                    } catch (e: Exception) {
                        log.log(Level.WARN, "tailnet: forwarder did not start: ${e.javaClass.simpleName}")
                        null
                    }
                }
                is TailnetState.Joining -> Unit
                else -> return null
            }
            if (wallClockMs() >= deadline) return null
            if (!runCatching { engine.awaitStatusChange(POLL_MS) }.getOrDefault(false)) sleeper(POLL_MS)
            s = refresh()
            // Idle after Joining: the thread that was waiting for the library gave up (its connect was
            // cancelled), so nobody is joining. Ask again, or this wait would run out its 45 s.
            if (s is TailnetState.Idle) s = ensureUp(cancelled)
        }
    }

    /** The newest connect's id. A late [abandonWarm] from an older connect finds it moved on and does nothing. */
    private var latestLease = 0L

    /** A fresh id for a connect (DEV-694). Cheap; call it once per connect. */
    fun newLease(): Long = synchronized(lock) {
        cancelHoldLocked() // a new connect owns the node now
        ++latestLease
    }

    private var cancelHold: () -> Unit = {}

    private fun cancelHoldLocked() {
        val c = cancelHold
        cancelHold = {}
        runCatching(c)
    }

    /**
     * The direct path won connect [lease]: the tailnet node, warmed or still joining, is not
     * needed, so stop it (battery). Unlike [sleep] it also stops a node that is still `Joining`
     * (a connect that started the tailnet at once leaves one running when direct wins), but never:
     * - for an older connect than the newest ([lease] stale), which may be using the node;
     * - while a single-use enrollment key is in flight (stopping would cancel the join and could
     *   spend the key with no identity saved).
     */
    fun abandonWarm(lease: Long): TailnetState = synchronized(lock) {
        if (lease != latestLease) return refreshLocked()
        // Still loading the library: nothing to stop yet (the engine's stop() is a no-op then), and the
        // thread waiting for it checks its own cancel probe before it joins.
        if (!engine.loaded) return refreshLocked()
        val s = refreshLocked()
        val keyInFlight = keys.load() != null || (s is TailnetState.Joining && joinUsedKey)
        if ((s is TailnetState.Up || s is TailnetState.Joining) && !keyInFlight) {
            if (s is TailnetState.Up && holdMs > 0) {
                cancelHoldLocked()
                log.log(Level.INFO, "tailnet: held warm for ${holdMs / 1000} s (Home Assistant answers directly)")
                cancelHold = holdTimer(holdMs) { releaseHeld(lease) }
                return s
            }
            stopLocked("Home Assistant answers directly")
            return refreshLocked()
        }
        s
    }

    /** The hold ran out: stop the node, unless a newer connect took it over, a key is joining, or it is gone already. */
    private fun releaseHeld(lease: Long) {
        synchronized(lock) {
            cancelHold = {}
            if (lease != latestLease) return
            val s = refreshLocked()
            val keyInFlight = keys.load() != null || (s is TailnetState.Joining && joinUsedKey)
            if ((s is TailnetState.Up || s is TailnetState.Joining) && !keyInFlight) {
                stopLocked("hold over")
                refreshLocked()
            }
        }
    }

    private fun stopLocked(why: String) {
        try {
            engine.stop()
        } catch (e: Exception) {
            log.log(Level.WARN, "tailnet: stop failed: ${e.javaClass.simpleName}")
        }
        log.log(Level.INFO, "tailnet: asleep ($why)")
    }

    /** The direct URL works: stop the node (battery). A join in progress is left to finish. */
    fun sleep(): TailnetState = synchronized(lock) {
        cancelHoldLocked()
        val s = refreshLocked()
        if (s is TailnetState.Up) {
            engine.stop()
            log.log(Level.INFO, "tailnet: asleep (Home Assistant answers directly)")
            return refreshLocked()
        }
        s
    }

    /**
     * The phone was paused: stop the node, up or still joining (unlike [sleep], which lets a join
     * finish). Never loads the library just to stop it. The stored identity stays.
     */
    fun pause(): TailnetState = synchronized(lock) {
        cancelHoldLocked()
        if (engine.built && engine.started) {
            try {
                engine.stop()
            } catch (e: Exception) {
                log.log(Level.WARN, "tailnet: stop on pause failed: ${e.javaClass.simpleName}")
            }
            log.log(Level.INFO, "tailnet: asleep (paused)")
        }
        refreshLocked()
    }

    // ---- internals (under lock) ----------------------------------------------------------

    /** Starts the join; the library is loaded by now (the caller waited for it outside the lock). */
    private fun joinLocked(key: String?) {
        try {
            engine.join(key, hostname())
            joinUsedKey = key != null
            log.log(Level.INFO, if (key != null) "tailnet: joining with the enrollment's key" else "tailnet: rejoining as this phone")
        } catch (e: Exception) {
            // "a join is already running" is the common one; the status says what happens next.
            log.log(Level.DEBUG, "tailnet: join not started: ${e.javaClass.simpleName}")
        }
    }

    /**
     * DEV-694: when THIS connect brought the node up (it loaded the library and joined), add tsnet's own phase times to the
     * timeline. A node that was already warm has stale phases from an earlier join: not added.
     */
    private fun noteJoinPhases() {
        runCatching {
            if (timeline.has(ConnectTimeline.Stage.LIBRARY_READY)) timeline.note(engine.joinPhases())
        }
    }

    /** A measurement must never disturb the connect: swallow anything. */
    private fun mark(stage: ConnectTimeline.Stage) {
        runCatching { timeline.mark(stage) }
    }

    private fun expiredLocked(pk: PendingKey): Boolean {
        val now = wallClockMs()
        val age = now - pk.savedAtMs
        // A clock that jumped far backwards cannot vouch for the key's age: treat it as old.
        if (age > MAX_KEY_AGE_MS || age < -CLOCK_SKEW_MS) {
            keys.clear()
            keyExpired = true
            log.log(Level.INFO, "tailnet: the enrollment's key expired unused and was deleted")
            return true
        }
        return false
    }

    private fun refreshLocked(): TailnetState {
        if (!engine.built) return setState(TailnetState.NotBuilt)
        keys.load()?.let { expiredLocked(it) }
        if (!engine.started) {
            return setState(if (keyExpired) TailnetState.KeyExpired else TailnetState.Idle(engine.hasIdentity(), keys.load() != null))
        }
        // Still loading (30 MB of Go runtime): report "joining" without waiting. [ready] would block
        // here, under the lock, and with it Setup's refresh, sleep, pause and the connector's poll.
        if (!engine.loaded) return setState(TailnetState.Joining())
        engine.ready()?.let { return setState(TailnetState.Failed(FailReason.LIBRARY, it)) }
        return when (val g = GoStatus.parse(safeStatus())) {
            is GoStatus.Up -> {
                if (keys.load() != null) {
                    keys.clear() // redeemed: a single-use key has no further use
                    log.log(Level.INFO, "tailnet: up; the enrollment's key is spent and deleted")
                }
                keyExpired = false
                identityDead = false
                refreshRouteLocked()
                setState(TailnetState.Up(g.address))
            }
            is GoStatus.Joining -> setState(TailnetState.Joining(g.needsApproval))
            is GoStatus.Failed -> {
                if (g.reason == FailReason.KEY_REJECTED || (g.reason == FailReason.NEEDS_LOGIN && joinUsedKey)) {
                    if (keys.load() != null) {
                        keys.clear()
                        log.log(Level.WARN, "tailnet: the key was refused and was deleted")
                    }
                }
                if (g.reason == FailReason.NEEDS_LOGIN && !joinUsedKey) identityDead = true
                // After the key aged out, the last attempt's failure is history: the fix is a new QR.
                setState(if (keyExpired) TailnetState.KeyExpired else TailnetState.Failed(g.reason, g.detail))
            }
            GoStatus.Idle -> setState(
                if (keyExpired) TailnetState.KeyExpired else TailnetState.Idle(engine.hasIdentity(), keys.load() != null),
            )
        }
    }

    private fun safeStatus(): String = try {
        engine.status()
    } catch (e: Exception) {
        "failed\terror\t${e.javaClass.simpleName}"
    }

    private fun setState(s: TailnetState): TailnetState {
        state = s
        if (s !is TailnetState.Up) route = RouteCheck.Unknown
        return s
    }

    private fun refreshRouteLocked() {
        val t = target()
        route = if (t == null || TailnetTarget.ipOf(t) == null) {
            RouteCheck.NotIp
        } else {
            try {
                RouteCheck.parse(engine.routeCheck(t))
            } catch (e: Exception) {
                RouteCheck.Unknown
            }
        }
    }

    companion object {
        /** Tailscale's pre-auth keys from the server live about an hour; delete ours a little before. */
        const val MAX_KEY_AGE_MS = 55L * 60 * 1000
        const val CLOCK_SKEW_MS = 5L * 60 * 1000
        const val POLL_MS = 250L
    }
}
