package com.meda.portico.core.ha

/** What kind of network the phone's default route is on, as far as the path recheck cares. */
enum class NetKind {
    WIFI, ETHERNET, CELLULAR, VPN, OTHER, NONE;

    /** A network the home LAN could be on. Mobile data and a VPN never are. */
    val canReachHome: Boolean get() = this == WIFI || this == ETHERNET

    companion object {
        /**
         * From the default network's transport flags. A VPN (another Tailscale app, a corporate
         * one) wins over the transport underneath it: its routes decide where `ha_url` goes, so it
         * is never taken for the home Wi-Fi.
         */
        fun of(wifi: Boolean, ethernet: Boolean, cellular: Boolean, vpn: Boolean): NetKind = when {
            vpn -> VPN
            wifi -> WIFI
            ethernet -> ETHERNET
            cellular -> CELLULAR
            else -> OTHER
        }
    }
}

/**
 * Should an open tailnet socket be re-routed direct-first now that the network changed? Pure.
 * An open socket never switches paths ([FallbackConnector]), and a healthy tailnet socket never
 * drops, so a phone that connected on mobile data would otherwise stay on the tailnet after it
 * joins the home Wi-Fi: the wrong status word, the tailnet node awake, and the frame-over-socket
 * call audio ([com.meda.portico.core.call.AwayMedia]) at home.
 */
object PathRecheckPolicy {
    /**
     * A change of the default network is a clear event, so the reconnect starts almost at once; this only lets one burst of
     * callbacks (a Wi-Fi join fires several within a second) be one recheck. It was 3 s; a phone measured 8 s from joining
     * Wi-Fi to the reconnect (DEV-705).
     */
    const val DEBOUNCE_MS = 500L

    /** Churn on the SAME kind of network (Wi-Fi to Wi-Fi) gets at most one recheck per this long. A change of kind never waits. */
    const val MIN_INTERVAL_MS = 5_000L

    /** First timed direct retry after the tailnet won because direct hit the deadline (slow, not absent). */
    const val RETRY_BASE_MS = 12_000L

    /** The retry delay doubles each time up to this. */
    const val RETRY_CAP_MS = 300_000L

    /** Timed retries stop after this many until the next network change (12+24+48+96+192+300 s, about 11 min). */
    const val MAX_TIMED_RECHECKS = 6

    /** The delay before timed retry number [done]+1 (done = retries already made), or null when the budget is spent. */
    fun retryDelayMs(done: Int): Long? =
        if (done >= MAX_TIMED_RECHECKS) null else minOf(RETRY_BASE_MS shl done.coerceAtMost(20), RETRY_CAP_MS)

    enum class Verdict {
        /** Call the existing reconnect (direct first, tailnet fallback as always). */
        RECHECK,

        /** A call is live: never interrupt it; look again when it ends. */
        DEFER_CALL,

        /** Rechecked a moment ago: look again once the interval has passed. */
        TOO_SOON,

        /** Nothing to do for this change. */
        SKIP,
    }

    /**
     * A recheck owed because the default network CHANGED. Whatever the path (a dead socket on the old network, a tailnet socket
     * that should now go direct, a socket waiting out a back-off with no network): the new network is a reason to connect now.
     * [lastKind] is the kind of network at the previous recheck, null before any.
     */
    fun changeVerdict(kind: NetKind, lastKind: NetKind?, callLive: Boolean, paused: Boolean, lastRecheckMs: Long?, nowMs: Long): Verdict = when {
        paused -> Verdict.SKIP
        kind == NetKind.NONE -> Verdict.SKIP // nothing to connect to; the return of signal is the next change
        callLive -> Verdict.DEFER_CALL
        lastRecheckMs != null && lastKind == kind && nowMs - lastRecheckMs < MIN_INTERVAL_MS -> Verdict.TOO_SOON
        else -> Verdict.RECHECK
    }

    /** The path a recheck on [kind] would aim for: direct where the home LAN can be, the tailnet elsewhere. */
    fun fitPath(kind: NetKind): LinkPath = if (kind.canReachHome) LinkPath.DIRECT else LinkPath.TAILNET

    /**
     * A change deferred by a call is paid when the call ends (DEV-833). If the socket is by then open on the path [fitPath] would
     * choose, the reconnect would only close a good socket (and stop every live camera tile for nothing): skip it.
     */
    fun deferredPaidVerdict(path: LinkPath?, kind: NetKind): Verdict? =
        if (path != null && path == fitPath(kind)) Verdict.SKIP else null

    /** The timed retry after a slow direct attempt. [path] is the open socket's path, null when no socket is open. */
    fun verdict(path: LinkPath?, kind: NetKind, callLive: Boolean, paused: Boolean, lastRecheckMs: Long?, nowMs: Long): Verdict = when {
        path != LinkPath.TAILNET -> Verdict.SKIP
        paused -> Verdict.SKIP
        !kind.canReachHome -> Verdict.SKIP
        callLive -> Verdict.DEFER_CALL
        lastRecheckMs != null && nowMs - lastRecheckMs < MIN_INTERVAL_MS -> Verdict.TOO_SOON
        else -> Verdict.RECHECK
    }
}

/**
 * The state around [PathRecheckPolicy]: debounce, defer while a call is live, bounded retries.
 *
 * Two triggers owe a recheck: a network CHANGE ([onNetwork]), and landing on the tailnet because
 * the direct attempt was SLOW rather than absent ([onLandedAfterDeadline]: a just-woken phone's
 * Wi-Fi). The second retries direct on [PathRecheckPolicy.retryDelayMs]'s backoff, only while the
 * network is Wi-Fi/Ethernet, never in a call or while paused, and gives up after
 * [PathRecheckPolicy.MAX_TIMED_RECHECKS] until the next network change or a direct socket resets it.
 *
 * Loop avoidance: a recheck is owed only because a network CHANGE was seen ([onNetwork] with a new
 * network id or kind; repeated callbacks for the same network, such as signal-strength updates,
 * are ignored). Once evaluated, the debt is paid whatever the outcome, so when the direct attempt
 * fails and the socket falls back to the tailnet, nothing re-arms until the next change. The only
 * repeat is [PathRecheckPolicy.Verdict.TOO_SOON], one delayed look per change.
 *
 * Callbacks come from the Android connectivity thread, [timer] tasks from the app's timer thread;
 * [reconnect] runs outside the lock. Every input read fails safe: unreadable means no recheck.
 */
class PathRechecker(
    private val timer: OneShotTimer,
    private val clockMs: () -> Long,
    private val path: () -> LinkPath?,
    private val callLive: () -> Boolean,
    private val paused: () -> Boolean,
    private val reconnect: () -> Unit,
    /** Each decision, as a word, for the connection log: why a network change did or did not reconnect (DEV-705). */
    private val trace: (String) -> Unit = {},
) {
    private val lock = Any()
    private var netId: Long? = null
    private var kind = NetKind.NONE
    private var owed = false
    private var lastRecheckMs: Long? = null
    private var lastRecheckKind: NetKind? = null
    private var retryOwed = false
    private var deferredByCall = false
    private var timedDone = 0
    private var cancel: () -> Unit = {}

    /** The default network is [id] of [kind] (0 / [NetKind.NONE] when it was lost). */
    fun onNetwork(id: Long, kind: NetKind) {
        synchronized(lock) {
            if (id == netId && kind == this.kind) return
            val first = netId == null
            netId = id
            this.kind = kind
            if (first) return // the first sighting is where we already are: the connect that just started used it
            owed = true
            deferredByCall = false
            timedDone = 0
            // Leading edge: the first change after a quiet moment is evaluated at once, on this callback's own wake-up. A timer
            // armed with a delay can be held back while a dozing phone's CPU sleeps (a measured Wi-Fi join waited 8.5 s for
            // the next network callback, DEV-705). Callbacks that follow within the debounce are one burst.
            val quiet = lastRecheckMs?.let { clockMs() - it >= PathRecheckPolicy.DEBOUNCE_MS } ?: true
            arm(if (quiet) 0L else PathRecheckPolicy.DEBOUNCE_MS)
        }
    }

    /** An answer or outgoing call ended: pay a recheck that a live call deferred. */
    fun onCallEnded() {
        synchronized(lock) { if (owed || retryOwed) arm(PathRecheckPolicy.DEBOUNCE_MS) }
    }

    /** The socket opened on the tailnet after the direct attempt hit its deadline: try direct again later. */
    fun onLandedAfterDeadline() {
        synchronized(lock) {
            val d = PathRecheckPolicy.retryDelayMs(timedDone) ?: return
            retryOwed = true
            if (!owed) arm(d) // a pending change recheck is sooner and covers it
        }
    }

    /** A direct socket opened: the slow start is over, the retry budget is whole again. */
    fun onDirectOpen() {
        synchronized(lock) {
            retryOwed = false
            timedDone = 0
            if (!owed) { cancel(); cancel = {} }
        }
    }

    private fun arm(delayMs: Long) {
        cancel()
        cancel = timer.schedule(delayMs) { evaluate() }
    }

    private fun evaluate() {
        val go = synchronized(lock) {
            cancel = {}
            if (!owed && !retryOwed) return
            val verdict = try {
                // a timed retry has its own backoff; the flap guard is for network changes
                if (owed) {
                    val v = PathRecheckPolicy.changeVerdict(kind, lastRecheckKind, callLive(), paused(), lastRecheckMs, clockMs())
                    when {
                        v == PathRecheckPolicy.Verdict.DEFER_CALL -> { deferredByCall = true; v }
                        v == PathRecheckPolicy.Verdict.RECHECK && deferredByCall ->
                            PathRecheckPolicy.deferredPaidVerdict(path(), kind) ?: v
                        else -> v
                    }
                } else {
                    PathRecheckPolicy.verdict(path(), kind, callLive(), paused(), null, clockMs())
                }
            } catch (_: Exception) {
                PathRecheckPolicy.Verdict.SKIP
            }
            say("recheck_" + verdict.name.lowercase())
            when (verdict) {
                PathRecheckPolicy.Verdict.RECHECK -> {
                    if (!owed) timedDone++
                    owed = false
                    deferredByCall = false
                    retryOwed = false
                    lastRecheckMs = clockMs()
                    lastRecheckKind = kind
                    true
                }
                PathRecheckPolicy.Verdict.DEFER_CALL -> false // onCallEnded re-arms; nothing polls
                PathRecheckPolicy.Verdict.TOO_SOON -> {
                    arm((PathRecheckPolicy.MIN_INTERVAL_MS - (clockMs() - (lastRecheckMs ?: 0L))).coerceAtLeast(1L))
                    false
                }
                PathRecheckPolicy.Verdict.SKIP -> {
                    owed = false
                    deferredByCall = false
                    retryOwed = false
                    false
                }
            }
        }
        if (go) runCatching(reconnect)
    }

    private fun say(word: String) {
        runCatching { trace(word) }
    }
}
