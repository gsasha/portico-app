package com.meda.portico.core.ha

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Which path the open socket took (DEV-557). */
enum class LinkPath { DIRECT, TAILNET }

/** A one-shot timer: [schedule] returns the cancel action. The app passes a ScheduledExecutorService. */
fun interface OneShotTimer {
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

/**
 * The path choice (DEV-557, docs/tailnet.md "Path choice"): `ha_url` directly first, the tailnet
 * second.
 *
 * - The direct attempt opens at once. If it opens within [directTimeoutMs], that is the socket
 *   ([LinkPath.DIRECT]) and [onDirectOpen] runs (the app puts the tailnet node to sleep).
 * - If it fails first (NXDOMAIN away from home, refused) it is closed and, when [tailnetUsable]
 *   says the tailnet could help, the [tailnet] connector is opened on [worker] (it may block while
 *   the node comes up). Without a usable tailnet the socket simply closes with the direct reason,
 *   exactly as before this class existed.
 * - If it is merely SLOW (still not open at the deadline) the attempts are STAGGERED, not
 *   exchanged: the tailnet starts and the direct attempt stays alive for up to [directGraceMs]
 *   more. Whichever opens first is the socket and the other is closed (a direct win also puts the
 *   tailnet node back to sleep, [onDirectOpen]). A just-woken phone whose Wi-Fi is slow used to
 *   lose to a cold tailnet node and could end up with neither ("offline", then "lan" on a later
 *   retry). The deadline is only "when to start the second path"; it no longer kills the first.
 *   [onLandedAfterDeadline] runs when the tailnet won after such a deadline, so the app can retry
 *   direct later ([PathRechecker]).
 * - Once a socket is open, its texts and its close reach the caller unchanged; a socket never
 *   switches paths while open (a later reconnect starts direct-first again).
 *
 * [onPath] reports the open path, and null when the socket closes. Pure: no Android, no OkHttp.
 */
class FallbackConnector(
    private val direct: Connector,
    private val tailnet: Connector?,
    private val tailnetUsable: () -> Boolean,
    private val timer: OneShotTimer,
    private val worker: Executor,
    private val directTimeoutMs: Long = DIRECT_TIMEOUT_MS,
    private val onPath: (LinkPath?) -> Unit = {},
    private val onDirectOpen: () -> Unit = {},
    /** Every attempt's outcome on each path, for Setup ([LinkDiagnostics.record]). */
    private val onAttempt: (PathAttempt) -> Unit = {},
    /**
     * The tailnet path's failure, refined with what the Go forwarder saw since the attempt began
     * ([LinkFailures.tailnet]); runs on a connector thread, never the UI's.
     */
    private val refineTailnet: (LinkFailure, Long) -> LinkFailure = { f, _ -> f },
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    /**
     * The phone is paused: [open] refuses (throws, so the caller's own failure path runs) and a
     * direct failure never wakes the tailnet. FAIL OPEN: an unreadable flag is "not paused".
     */
    private val paused: () -> Boolean = { false },
    /** How long a direct attempt that missed the deadline may go on racing the tailnet before it is closed. */
    private val directGraceMs: Long = DIRECT_GRACE_MS,
    /** The tailnet became the socket although the direct attempt had hit the deadline (not a hard failure). */
    private val onLandedAfterDeadline: () -> Unit = {},
    /** Where the time of each connect goes (DEV-694 P0); written here and by the tailnet controller, read by Setup. */
    private val timeline: ConnectTimeline = ConnectTimeline(),
    /**
     * DEV-694: the path hint for this connect ([PathHint.judge]), asked once per [open]. FAIL OPEN:
     * a throw is [PathMode.DIRECT_FIRST], today's behaviour. [PathMode.TAILNET_FIRST] starts the
     * tailnet connector at once, alongside the direct attempt, instead of after the deadline.
     */
    private val hint: () -> Hint = { Hint(PathMode.DIRECT_FIRST, "no hint") },
    /**
     * The direct attempt of a MOBILE-ONLY connect ([Hint.mobileOnly]) opened (true) or failed for a
     * reachability reason (false): it teaches [DirectWorksOutside].
     */
    private val onMobileDirect: (Boolean) -> Unit = {},
    /** A fresh id for each connect, so a late "the tailnet is not needed" cannot stop a node a newer connect is using. */
    private val newLease: () -> Long = { 0L },
    /** The direct path won with this lease: the tailnet node, if one was warmed or is still joining, is not needed. */
    private val onDirectWon: (Long) -> Unit = {},
    /**
     * DEV-811: [hint] for a CANDIDATE socket (the link race): the same judgement without its side effects (no connection-log hint, no
     * journal line: they describe the working socket's connect). Defaults to [hint].
     */
    private val peekHint: (() -> Hint)? = null,
) : Connector {

    private fun isPaused(): Boolean = try {
        paused()
    } catch (_: Exception) {
        false
    }

    override fun open(url: String, listener: WsListener): WsTransport = openAttempt(url, listener, candidate = false)

    /** DEV-811: a second socket beside the working one: it publishes no path, wakes or sleeps no node and restarts no timeline until [WsTransport.promote]. */
    override fun openCandidate(url: String, listener: WsListener): WsTransport = openAttempt(url, listener, candidate = true)

    private fun openAttempt(url: String, listener: WsListener, candidate: Boolean): WsTransport {
        if (isPaused()) throw IllegalStateException("paused")
        if (!candidate) runCatching { timeline.begin() }
        val h = try {
            if (candidate) (peekHint ?: hint)() else hint()
        } catch (_: Exception) {
            Hint(PathMode.DIRECT_FIRST, "hint failed")
        }
        val a = Attempt(url, listener, h, if (candidate) 0L else runCatching(newLease).getOrDefault(0L), candidate)
        a.start() // the direct connector may throw (a refused URL): that reaches the caller as before
        return a
    }

    private enum class Phase { DIRECT, TAILNET, OPEN, CLOSED }

    /**
     * 0.6.51: [onPath] is ONE global value, but each socket reports its own close asynchronously, after the caller has already opened the
     * next socket (a recheck leaving the tailnet, a network change). The old socket's late `onPath(null)` then erased the new socket's
     * path: the link was CONNECTED with an unknown path, the live plan opened nothing ("unknown path attempts and says nothing") and the
     * top bar said "connected" instead of "LAN". So only the attempt that published the current path may clear it.
     */
    private val pathLock = Any()
    private var pathOwner: Attempt? = null

    private var publishedPath: LinkPath? = null

    private fun publishPath(a: Attempt, p: LinkPath) = synchronized(pathLock) {
        pathOwner = a
        publishedPath = p
        onPath(p)
    }

    private fun clearPath(a: Attempt) = synchronized(pathLock) {
        if (pathOwner === a || pathOwner == null) {
            pathOwner = null
            publishedPath = null
            onPath(null)
        }
    }

    private inner class Attempt(
        private val url: String,
        private val listener: WsListener,
        private val hint: Hint,
        @Volatile private var lease: Long,
        /** DEV-811: opened beside a working socket; cleared by [promote]. Guarded by [lock]. */
        private var candidate: Boolean = false,
    ) : WsTransport {
        private val lock = Any()
        private var phase = Phase.DIRECT
        private var openPath: LinkPath? = null
        private var directT: WsTransport? = null
        private var tailnetT: WsTransport? = null
        private val tailnetAssigned = CountDownLatch(1)
        private var closedByCaller = false
        private var reported = false
        private var cancelTimer: () -> Unit = {}
        private var cancelGrace: () -> Unit = {}
        private var directLate = false // missed the deadline, still connecting alongside the tailnet
        private var deadlineHit = false
        private var tailnetDone = false
        private var tailnetWhy = ""
        private var tailnetCancelled = false
        private var directWhy = ""
        private var tailnetStartMs = 0L
        /** DEV-811: what a candidate would have said (attempt outcomes, "landed after the deadline"); said at [promote], dropped if it loses. */
        private val deferred = ArrayList<Pair<LinkPath, LinkFailure?>>()
        private var deferredLanded = false
        private var tailnetTouched = false
        private val failures = HashMap<LinkPath, LinkFailure>()
        private val recorded = HashSet<LinkPath>() // paths whose outcome went to [onAttempt]

        fun start() {
            val t = direct.open(url, PathListener(LinkPath.DIRECT))
            synchronized(lock) {
                directT = t
                if (phase != Phase.DIRECT) return // it already failed and we moved on
            }
            if (hint.mode == PathMode.TAILNET_FIRST && startTailnetFirst()) return
            val cancel = timer.schedule(directTimeoutMs) { directTimedOut() }
            synchronized(lock) {
                if (phase == Phase.DIRECT) cancelTimer = cancel else cancel()
            }
        }

        /**
         * DEV-694: mobile data is not the home LAN, so start the tailnet now instead of after the
         * deadline. It is the same state a SLOW direct attempt is moved to at its deadline
         * (`TAILNET` with [directLate]): the direct attempt stays alive alongside, the first to
         * open wins and the other is closed. Direct keeps today's deadline: [directGraceExpired]
         * closes it after [directTimeoutMs], so a direct attempt that blackholes still lets the
         * failure be reported as soon as it did before. False: not applicable, carry on direct-first.
         *
         * Unlike the deadline path it does not set [deadlineHit]: a tailnet win here is not "direct
         * was slow", so [onLandedAfterDeadline] (which arms the PathRechecker's retry of direct) does
         * not fire; on mobile data there is nothing to retry.
         */
        private fun startTailnetFirst(): Boolean {
            synchronized(lock) {
                if (phase != Phase.DIRECT || closedByCaller) return false
                if (tailnet == null || isPaused() || !usable()) return false
                phase = Phase.TAILNET
                directLate = true
                directWhy = "the tailnet was started first (mobile data)"
            }
            worker.execute { openTailnet() }
            val cancel = timer.schedule(hint.directCapMs ?: directTimeoutMs) { directGraceExpired() }
            synchronized(lock) { if (directLate) cancelGrace = cancel else cancel() }
            return true
        }

        private fun directTimedOut() {
            val t = synchronized(lock) {
                if (phase != Phase.DIRECT) return
                failures[LinkPath.DIRECT] = LinkFailures.DIRECT_DEADLINE
                deadlineHit = true
                directT
            }
            directFailed("no answer within $directTimeoutMs ms", keepAlive = true)
            val kept = synchronized(lock) { directLate }
            if (kept) {
                val cancel = timer.schedule(directGraceMs) { directGraceExpired() }
                synchronized(lock) { if (directLate) cancelGrace = cancel else cancel() }
            } else {
                t?.close(1000, "direct path timed out") // its late onClosed is ignored: phase moved on
            }
        }

        /** The slow direct attempt got its extra time and is still not open: close it, as the deadline once did. */
        private fun directGraceExpired() {
            val t: WsTransport?
            val finish: Boolean
            val unrecorded: Boolean
            synchronized(lock) {
                if (!directLate || phase != Phase.TAILNET) return
                directLate = false
                t = directT
                finish = tailnetDone
                // started alongside the tailnet (mobile data): the deadline is the direct attempt's only outcome
                unrecorded = LinkPath.DIRECT !in recorded
                if (unrecorded) {
                    failures[LinkPath.DIRECT] = LinkFailures.DIRECT_DEADLINE
                    directWhy = "no answer within ${hint.directCapMs ?: directTimeoutMs} ms"
                }
            }
            t?.close(1000, "direct path timed out")
            if (unrecorded) attempt(LinkPath.DIRECT, failureOf(LinkPath.DIRECT))
            if (finish) report("direct: $directWhy; tailnet: $tailnetWhy")
        }

        private fun directFailed(reason: String, keepAlive: Boolean = false) {
            val goTailnet: Boolean
            synchronized(lock) {
                if (phase != Phase.DIRECT) return
                cancelTimer()
                directWhy = reason
                goTailnet = tailnet != null && !closedByCaller && !isPaused() && usable()
                phase = if (goTailnet) Phase.TAILNET else Phase.CLOSED
                directLate = goTailnet && keepAlive
            }
            attempt(LinkPath.DIRECT, failureOf(LinkPath.DIRECT))
            if (goTailnet) worker.execute { openTailnet() } else report("direct: $reason")
        }

        private fun usable(): Boolean = try {
            tailnetUsable()
        } catch (_: Exception) {
            false
        }

        private fun openTailnet() {
            synchronized(lock) { tailnetStartMs = wallClockMs() }
            synchronized(lock) { tailnetTouched = true }
            if (!synchronized(lock) { candidate }) runCatching { timeline.mark(ConnectTimeline.Stage.TAILNET_STARTED) }
            val t = try {
                tailnet!!.open(url, PathListener(LinkPath.TAILNET)) {
                    // an abandoned attempt must free the (single) tailnet worker, not wait out the node
                    synchronized(lock) { closedByCaller || tailnetCancelled }
                }
            } catch (e: Exception) {
                tailnetAssigned.countDown()
                val stillTrying = synchronized(lock) { phase == Phase.TAILNET }
                if (stillTrying) {
                    val f = LinkFailures.classify(listOf(ThrowableInfo(e.javaClass.simpleName, e.message)))
                    attempt(LinkPath.TAILNET, safeRefine(f))
                    tailnetFailed(e.message ?: e.javaClass.simpleName)
                }
                return
            }
            val closeNow = synchronized(lock) {
                tailnetT = t
                closedByCaller || tailnetCancelled
            }
            tailnetAssigned.countDown()
            if (closeNow) t.close(1000, "closed while connecting")
        }

        /** The tailnet path is over. Report now, unless a slow direct attempt may still open. */
        private fun tailnetFailed(why: String) {
            val wait = synchronized(lock) {
                tailnetDone = true
                tailnetWhy = why
                directLate
            }
            if (!wait) report("direct: $directWhy; tailnet: $why")
        }

        private fun failureOf(path: LinkPath): LinkFailure =
            synchronized(lock) { failures[path] } ?: LinkFailure(FailureClass.CLOSED_EARLY)

        private fun safeRefine(f: LinkFailure): LinkFailure = try {
            refineTailnet(f, synchronized(lock) { tailnetStartMs })
        } catch (_: Exception) {
            f
        }

        private fun attempt(path: LinkPath, failure: LinkFailure?) {
            val held = synchronized(lock) {
                recorded.add(path)
                if (candidate) deferred += path to failure
                candidate
            }
            if (held) return // a candidate's outcomes are the working socket's business only if it wins
            attemptNow(path, failure)
        }

        private fun attemptNow(path: LinkPath, failure: LinkFailure?) {
            val superseded = failure?.cls == FailureClass.SUPERSEDED
            if (path == LinkPath.DIRECT && hint.mobileOnly && !superseded) {
                if (failure == null) {
                    runCatching { onMobileDirect(true) }
                } else if (failure.cls in REACHABILITY && failure !== LinkFailures.DIRECT_DEADLINE) {
                    // The 5 s deadline alone is NOT evidence: a public ha_url that is merely slow on 4G would
                    // otherwise count as "unreachable" and switch the speculative tailnet starts back on.
                    runCatching { onMobileDirect(false) }
                }
            }
            if (!superseded) runCatching {
                timeline.mark(
                    when (path) {
                        LinkPath.DIRECT -> if (failure == null) ConnectTimeline.Stage.DIRECT_OPENED else ConnectTimeline.Stage.DIRECT_FAILED
                        LinkPath.TAILNET -> if (failure == null) ConnectTimeline.Stage.TAILNET_OPENED else ConnectTimeline.Stage.TAILNET_FAILED
                    },
                    failure?.cls?.name,
                )
            }
            runCatching { onAttempt(PathAttempt(path, wallClockMs(), failure)) }
        }

        private fun report(reason: String) {
            synchronized(lock) {
                if (reported) return
                reported = true
                phase = Phase.CLOSED
            }
            if (!synchronized(lock) { candidate }) clearPath(this)
            listener.onClosed(reason)
        }

        /** One listener per path; every callback checks that its path is the live one. */
        private inner class PathListener(private val path: LinkPath) : WsListener {
            private val trying = if (path == LinkPath.DIRECT) Phase.DIRECT else Phase.TAILNET

            override fun onOpen() {
                val loser: WsTransport?
                val landedLate: Boolean
                val superseded: LinkPath?
                synchronized(lock) {
                    val lateDirect = path == LinkPath.DIRECT && phase == Phase.TAILNET && directLate
                    if ((phase != trying && !lateDirect) || closedByCaller) return
                    // the other path, if still connecting alongside, loses
                    loser = if (path == LinkPath.DIRECT) {
                        tailnetCancelled = true
                        tailnetT
                    } else if (directLate) {
                        directT
                    } else {
                        null
                    }
                    landedLate = path == LinkPath.TAILNET && deadlineHit
                    // The other path, if it was started and has no outcome yet, was simply not needed.
                    superseded = if (path == LinkPath.DIRECT) {
                        LinkPath.TAILNET.takeIf { phase == Phase.TAILNET && it !in recorded }
                    } else {
                        LinkPath.DIRECT.takeIf { directLate && it !in recorded }
                    }
                    phase = Phase.OPEN
                    openPath = path
                    directLate = false
                    cancelTimer()
                    cancelGrace()
                }
                loser?.let { l -> runCatching { l.close(1000, if (path == LinkPath.DIRECT) "direct opened first" else "tailnet opened first") } }
                val cand = synchronized(lock) { candidate }
                if (!cand) publishPath(this@Attempt, path)
                attempt(path, null)
                superseded?.let { attempt(it, LinkFailure(FailureClass.SUPERSEDED)) }
                if (path == LinkPath.DIRECT && !cand) {
                    runCatching(onDirectOpen)
                    runCatching { onDirectWon(lease) }
                }
                if (landedLate && !cand) runCatching(onLandedAfterDeadline)
                if (landedLate && cand) synchronized(lock) { deferredLanded = true }
                listener.onOpen()
            }

            override fun onText(text: String) {
                val live = synchronized(lock) { phase == Phase.OPEN && openPath == path }
                if (live) listener.onText(text)
            }

            override fun onFailure(failure: LinkFailure) {
                synchronized(lock) { if (failures[path] == null || phase == trying) failures[path] = failure }
            }

            override fun onClosed(reason: String) {
                val p = synchronized(lock) { if (phase == Phase.OPEN && openPath != path) null else phase }
                when (p) {
                    Phase.OPEN -> report(reason)
                    Phase.DIRECT -> if (path == LinkPath.DIRECT) directFailed(reason)
                    Phase.TAILNET -> if (path == LinkPath.TAILNET) {
                        attempt(LinkPath.TAILNET, safeRefine(failureOf(LinkPath.TAILNET)))
                        tailnetFailed(reason)
                    } else {
                        // the slow direct attempt failed for real while the tailnet is still coming up
                        val unrecorded: Boolean
                        val finish = synchronized(lock) {
                            if (!directLate) return
                            directLate = false
                            cancelGrace()
                            unrecorded = LinkPath.DIRECT !in recorded
                            if (unrecorded) directWhy = reason
                            tailnetDone
                        }
                        // started alongside the tailnet (mobile data): this is the direct attempt's only outcome
                        if (unrecorded) attempt(LinkPath.DIRECT, failureOf(LinkPath.DIRECT))
                        if (finish) report("direct: $directWhy; tailnet: $tailnetWhy")
                    }
                    else -> Unit // a late close from the abandoned direct attempt
                }
            }
        }

        override fun send(text: String): Boolean {
            val path = synchronized(lock) { if (phase == Phase.OPEN) openPath else null } ?: return false
            if (path == LinkPath.TAILNET) tailnetAssigned.await(2, TimeUnit.SECONDS) // OkHttp may open before open() returned
            val t = synchronized(lock) { if (path == LinkPath.DIRECT) directT else tailnetT }
            return t?.send(text) ?: false
        }

        override fun queueSize(): Long {
            val t = synchronized(lock) { if (phase != Phase.OPEN) null else if (openPath == LinkPath.DIRECT) directT else tailnetT }
            return t?.queueSize() ?: 0L
        }

        /** DEV-811: the race winner: publish its path and, if direct, let the tailnet node go, as [PathListener.onOpen] does for a first socket. */
        override fun promote() {
            val path = synchronized(lock) {
                if (!candidate) return
                candidate = false
                if (phase == Phase.OPEN) openPath else null
            } ?: return
            lease = runCatching(newLease).getOrDefault(0L) // a candidate takes no lease (it would cancel the node's warm-hold) until it is the socket
            runCatching { timeline.begin() } // the promoted connect's own marks (and its `connected` line) start now, not at the working socket's connect
            val replay = synchronized(lock) { deferred.toList().also { deferred.clear() } }
            val landed = synchronized(lock) { deferredLanded }
            publishPath(this, path)
            replay.forEach { (p, f) -> attemptNow(p, f) }
            if (path == LinkPath.DIRECT) {
                runCatching(onDirectOpen)
                runCatching { onDirectWon(lease) }
            }
            if (landed) runCatching(onLandedAfterDeadline)
        }

        override fun close(code: Int, reason: String) {
            val toClose: List<WsTransport>
            val connecting: Boolean
            synchronized(lock) {
                closedByCaller = true
                cancelTimer()
                cancelGrace()
                toClose = listOfNotNull(directT, tailnetT)
                connecting = phase == Phase.DIRECT || phase == Phase.TAILNET
            }
            toClose.forEach { runCatching { it.close(code, reason) } }
            // A candidate that touched the tailnet node and lost, beside a working DIRECT socket: the node is not needed.
            val lostCandidate = synchronized(lock) { candidate && tailnetTouched }
            if (lostCandidate && synchronized(pathLock) { publishedPath } == LinkPath.DIRECT) {
                runCatching { onDirectWon(runCatching(newLease).getOrDefault(0L)) }
            }
            // An open socket reports its own close (as OkHttpConnector does); one still connecting
            // has nobody to report it but us.
            if (connecting) report("closed while connecting: $reason")
        }
    }

    companion object {
        /** Long enough for a TLS handshake on a slow home Wi-Fi; short enough to fall back promptly. */
        const val DIRECT_TIMEOUT_MS = 5_000L

        /**
         * How much longer the slow direct attempt may race the tailnet after [DIRECT_TIMEOUT_MS].
         * Matches OkHttp's 15 s connect timeout (OkHttpConnector) so a direct attempt that is
         * merely slow is never cut off sooner than before the stagger, and a silent one cannot hold
         * a failed tailnet attempt open forever.
         */
        const val DIRECT_GRACE_MS = 10_000L

        /** Failures that say "this network does not reach Home Assistant", as opposed to TLS, auth or HTTP ones. */
        private val REACHABILITY = setOf(FailureClass.NAME_NOT_RESOLVED, FailureClass.REFUSED, FailureClass.TIMED_OUT, FailureClass.UNREACHABLE)
    }
}
