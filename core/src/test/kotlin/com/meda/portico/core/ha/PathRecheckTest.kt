package com.meda.portico.core.ha

import com.meda.portico.core.ha.PathRecheckPolicy.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The path recheck: the pure policy table, then the debounce/defer state machine around it. */
class PathRecheckTest {
    private fun v(
        path: LinkPath? = LinkPath.TAILNET,
        kind: NetKind = NetKind.WIFI,
        call: Boolean = false,
        paused: Boolean = false,
        last: Long? = null,
        now: Long = 100_000,
    ) = PathRecheckPolicy.verdict(path, kind, call, paused, last, now)

    // ---- policy ----

    @Test fun `tailnet then wifi rechecks`() = assertEquals(Verdict.RECHECK, v())
    @Test fun `tailnet then ethernet rechecks`() = assertEquals(Verdict.RECHECK, v(kind = NetKind.ETHERNET))
    @Test fun `cellular never`() = assertEquals(Verdict.SKIP, v(kind = NetKind.CELLULAR))
    @Test fun `vpn never`() = assertEquals(Verdict.SKIP, v(kind = NetKind.VPN))
    @Test fun `other and none never`() {
        assertEquals(Verdict.SKIP, v(kind = NetKind.OTHER))
        assertEquals(Verdict.SKIP, v(kind = NetKind.NONE))
    }
    @Test fun `already direct never`() = assertEquals(Verdict.SKIP, v(path = LinkPath.DIRECT))
    @Test fun `link not open is left to the normal retry`() = assertEquals(Verdict.SKIP, v(path = null))
    @Test fun `paused never`() = assertEquals(Verdict.SKIP, v(paused = true))
    @Test fun `a live call defers`() = assertEquals(Verdict.DEFER_CALL, v(call = true))
    @Test fun `paused outranks a live call`() = assertEquals(Verdict.SKIP, v(call = true, paused = true))
    @Test fun `cellular outranks a live call`() = assertEquals(Verdict.SKIP, v(call = true, kind = NetKind.CELLULAR))
    @Test fun `a recheck just done is too soon`() =
        assertEquals(Verdict.TOO_SOON, v(last = 100_000 - 1_000))
    @Test fun `an old recheck does not block`() =
        assertEquals(Verdict.RECHECK, v(last = 100_000 - PathRecheckPolicy.MIN_INTERVAL_MS))

    @Test fun `kind of transport flags - vpn wins over the transport underneath`() {
        assertEquals(NetKind.VPN, NetKind.of(wifi = true, ethernet = false, cellular = false, vpn = true))
        assertEquals(NetKind.WIFI, NetKind.of(true, false, false, false))
        assertEquals(NetKind.ETHERNET, NetKind.of(false, true, false, false))
        assertEquals(NetKind.CELLULAR, NetKind.of(false, false, true, false))
        assertEquals(NetKind.OTHER, NetKind.of(false, false, false, false))
    }

    // ---- state machine ----

    private class Timer : OneShotTimer {
        var task: (() -> Unit)? = null
        var delay = -1L
        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            this.task = task
            delay = delayMs
            return { this.task = null }
        }
        fun fire() { val t = task!!; task = null; t() }
        val armed get() = task != null
    }

    private val timer = Timer()
    private var now = 1_000_000L
    private var path: LinkPath? = LinkPath.TAILNET
    private var call = false
    private var paused = false
    private var reconnects = 0

    /** A rechecker that has already seen its starting network (the first sighting only primes it). */
    private fun rc() = PathRechecker(timer, { now }, { path }, { call }, { paused }, { reconnects++ }).also { it.onNetwork(99, NetKind.OTHER) }

    @Test fun `a change to wifi rechecks once, after the debounce`() {
        val r = rc()
        r.onNetwork(7, NetKind.WIFI)
        assertEquals(0L, timer.delay) // leading edge: nothing recent, so now
        assertEquals(0, reconnects)
        timer.fire()
        assertEquals(1, reconnects)
        assertNull(timer.task)
    }

    @Test fun `a burst of callbacks is one recheck`() {
        val r = rc()
        r.onNetwork(7, NetKind.WIFI)
        r.onNetwork(7, NetKind.WIFI) // same network, same kind: signal-strength chatter
        r.onNetwork(7, NetKind.WIFI)
        timer.fire()
        assertEquals(1, reconnects)
    }

    @Test fun `chatter on the same network after the recheck does nothing`() {
        val r = rc()
        r.onNetwork(7, NetKind.WIFI)
        timer.fire()
        r.onNetwork(7, NetKind.WIFI)
        assertEquals(false, timer.armed)
        assertEquals(1, reconnects)
    }

    @Test fun `landing on the tailnet again does not loop until the next change`() {
        val r = rc()
        r.onNetwork(7, NetKind.WIFI)
        timer.fire()
        path = LinkPath.TAILNET // direct failed again, fell back
        now += 60_000
        assertEquals(false, timer.armed)
        r.onNetwork(7, NetKind.WIFI)
        assertEquals(false, timer.armed)
        assertEquals(1, reconnects)
        r.onNetwork(8, NetKind.WIFI) // a genuinely new network
        timer.fire()
        assertEquals(2, reconnects)
    }

    @Test fun `a switch to mobile data and back to wifi each reconnect, a change of kind never waits`() {
        val r = rc()
        r.onNetwork(7, NetKind.WIFI)
        timer.fire()
        now += 1_000
        r.onNetwork(3, NetKind.CELLULAR)
        assertEquals(0L, timer.delay)
        timer.fire()
        now += 1_000
        r.onNetwork(8, NetKind.WIFI)
        timer.fire()
        assertEquals(3, reconnects)
    }

    @Test fun `the first network the machine sees only primes it - no second connect at start`() {
        val r = PathRechecker(timer, { now }, { path }, { call }, { paused }, { reconnects++ })
        r.onNetwork(7, NetKind.WIFI)
        assertEquals(false, timer.armed)
        assertEquals(0, reconnects)
        r.onNetwork(8, NetKind.CELLULAR)
        timer.fire()
        assertEquals(1, reconnects)
    }

    @Test fun `signal back after none reconnects at once even with no socket open`() {
        val r = rc()
        r.onNetwork(7, NetKind.WIFI)
        timer.fire()
        r.onNetwork(0, NetKind.NONE)
        timer.fire()
        path = null // the socket is down, waiting out a back-off
        now += 40_000
        r.onNetwork(9, NetKind.CELLULAR)
        timer.fire()
        assertEquals(2, reconnects)
    }

    @Test fun `change policy table`() {
        fun c(kind: NetKind = NetKind.WIFI, last: NetKind? = null, call: Boolean = false, paused: Boolean = false, at: Long? = null, now: Long = 100_000) =
            PathRecheckPolicy.changeVerdict(kind, last, call, paused, at, now)
        assertEquals(Verdict.RECHECK, c())
        assertEquals(Verdict.RECHECK, c(NetKind.CELLULAR))
        assertEquals(Verdict.RECHECK, c(NetKind.VPN))
        assertEquals(Verdict.SKIP, c(NetKind.NONE))
        assertEquals(Verdict.SKIP, c(paused = true, call = true))
        assertEquals(Verdict.DEFER_CALL, c(call = true))
        assertEquals(Verdict.TOO_SOON, c(last = NetKind.WIFI, at = 98_000))
        assertEquals(Verdict.RECHECK, c(last = NetKind.CELLULAR, at = 99_900)) // a change of kind never waits
        assertEquals(Verdict.RECHECK, c(last = NetKind.WIFI, at = 90_000))
    }

    @Test fun `a lost network is a change but never a recheck`() {
        val r = rc()
        r.onNetwork(7, NetKind.WIFI)
        timer.fire()
        r.onNetwork(0, NetKind.NONE)
        timer.fire()
        assertEquals(1, reconnects)
    }

    @Test fun `a live call defers and its end rechecks`() {
        val r = rc()
        call = true
        r.onNetwork(7, NetKind.WIFI)
        timer.fire()
        assertEquals(0, reconnects)
        assertEquals(false, timer.armed) // nothing polls during the call
        call = false
        r.onCallEnded()
        assertEquals(PathRecheckPolicy.DEBOUNCE_MS, timer.delay)
        timer.fire()
        assertEquals(1, reconnects)
    }

    @Test fun `DEV-833 a change deferred by a call is not paid when the socket already sits on the fit path`() {
        val r = rc()
        call = true
        path = LinkPath.DIRECT
        r.onNetwork(7, NetKind.WIFI)
        timer.fire()
        call = false
        r.onCallEnded()
        timer.fire()
        assertEquals(0, reconnects) // a reconnect would close the good socket and stop the live tiles
        path = LinkPath.DIRECT
        r.onNetwork(8, NetKind.WIFI)
        call = true
        timer.fire()
        call = false
        path = LinkPath.TAILNET // wrong path for wifi: the deferred recheck is still paid
        r.onCallEnded()
        timer.fire()
        assertEquals(1, reconnects)
    }

    @Test fun `a call that ends with no change seen does nothing`() {
        val r = rc()
        r.onCallEnded()
        assertEquals(false, timer.armed)
    }

    @Test fun `the call ends on cellular - the deferred change is still paid once`() {
        val r = rc()
        call = true
        r.onNetwork(7, NetKind.WIFI)
        timer.fire()
        r.onNetwork(3, NetKind.CELLULAR) // left the house again mid-call
        timer.fire()
        call = false
        path = LinkPath.DIRECT // not the fit path on mobile data
        r.onCallEnded()
        assertEquals(true, timer.armed) // the change is still owed: the call's end pays it
        timer.fire()
        assertEquals(1, reconnects)
    }

    @Test fun `paused never, and nothing is retried after`() {
        val r = rc()
        paused = true
        r.onNetwork(7, NetKind.WIFI)
        timer.fire()
        assertEquals(0, reconnects)
        paused = false
        r.onCallEnded()
        assertEquals(false, timer.armed)
    }

    @Test fun `a new network rechecks whatever the open path is (machine)`() {
        val r = rc()
        path = LinkPath.DIRECT // a direct socket on the old network can be dead too
        r.onNetwork(7, NetKind.WIFI)
        timer.fire()
        assertEquals(1, reconnects)
    }

    @Test fun `flapping wifi is held to the minimum interval, then rechecks once`() {
        val r = rc()
        r.onNetwork(7, NetKind.WIFI)
        timer.fire()
        assertEquals(1, reconnects)
        now += 2_000
        r.onNetwork(8, NetKind.WIFI)
        timer.fire()
        assertEquals(1, reconnects) // too soon
        assertEquals(true, timer.armed)
        assertEquals(PathRecheckPolicy.MIN_INTERVAL_MS - 2_000, timer.delay)
        now += timer.delay
        timer.fire()
        assertEquals(2, reconnects)
    }

    // ---- timed retries after a SLOW direct attempt (landed on the tailnet at the deadline) ----

    @Test fun `retry delays double from 12 s to a 5 minute cap and stop after six`() {
        val d = (0..6).map { PathRecheckPolicy.retryDelayMs(it) }
        assertEquals(listOf<Long?>(12_000, 24_000, 48_000, 96_000, 192_000, 300_000, null), d)
    }

    @Test fun `landing late on wifi retries direct after the base delay`() {
        val r = rc()
        r.onNetwork(7, NetKind.WIFI)
        timer.fire() // the change recheck
        reconnects = 0
        r.onLandedAfterDeadline()
        assertEquals(12_000L, timer.delay)
        timer.fire()
        assertEquals(1, reconnects)
    }

    @Test fun `a second late landing backs off to 24 s, and the budget ends after six`() {
        val r = rc()
        r.onNetwork(7, NetKind.WIFI)
        timer.fire()
        reconnects = 0
        val seen = mutableListOf<Long>()
        repeat(6) {
            r.onLandedAfterDeadline()
            seen += timer.delay
            timer.fire()
        }
        assertEquals(listOf(12_000L, 24_000L, 48_000L, 96_000L, 192_000L, 300_000L), seen)
        assertEquals(6, reconnects)
        r.onLandedAfterDeadline()
        assertEquals(false, timer.armed) // spent: no hammering
        r.onNetwork(9, NetKind.WIFI) // the next change starts over
        assertEquals(true, timer.armed)
    }

    @Test fun `a direct socket resets the budget`() {
        val r = rc()
        r.onNetwork(7, NetKind.WIFI)
        timer.fire()
        r.onLandedAfterDeadline()
        timer.fire()
        r.onDirectOpen()
        r.onLandedAfterDeadline()
        assertEquals(12_000L, timer.delay)
    }

    @Test fun `late landing on cellular never retries`() {
        val r = rc()
        r.onNetwork(3, NetKind.CELLULAR)
        timer.fire()
        reconnects = 0 // the change recheck itself is covered elsewhere
        r.onLandedAfterDeadline()
        timer.fire()
        assertEquals(0, reconnects)
    }

    @Test fun `late landing while paused never retries`() {
        val r = rc()
        r.onNetwork(7, NetKind.WIFI)
        timer.fire()
        reconnects = 0
        paused = true
        r.onLandedAfterDeadline()
        timer.fire()
        assertEquals(0, reconnects)
    }

    @Test fun `late landing during a call waits for the call to end`() {
        val r = rc()
        r.onNetwork(7, NetKind.WIFI)
        timer.fire()
        reconnects = 0
        call = true
        r.onLandedAfterDeadline()
        timer.fire()
        assertEquals(0, reconnects)
        assertEquals(false, timer.armed)
        call = false
        r.onCallEnded()
        timer.fire()
        assertEquals(1, reconnects)
    }

    @Test fun `the timed retry ignores the flap guard but a change recheck does not`() {
        val r = rc()
        r.onNetwork(7, NetKind.WIFI)
        timer.fire() // lastRecheck = now
        reconnects = 0
        now += 1_000
        r.onLandedAfterDeadline()
        timer.fire()
        assertEquals(1, reconnects)
    }

    @Test fun `a throwing reconnect does not break the machine`() {
        val r = PathRechecker(timer, { now }, { path }, { call }, { paused }, { throw IllegalStateException("x") })
        r.onNetwork(99, NetKind.OTHER)
        r.onNetwork(7, NetKind.WIFI)
        timer.fire()
        r.onNetwork(8, NetKind.WIFI)
        assertEquals(true, timer.armed)
    }

    @Test fun `an unreadable input fails safe - no recheck`() {
        val r = PathRechecker(timer, { now }, { path }, { throw IllegalStateException("x") }, { paused }, { reconnects++ })
        r.onNetwork(99, NetKind.OTHER)
        r.onNetwork(7, NetKind.WIFI)
        timer.fire()
        assertEquals(0, reconnects)
    }

    @Test fun `the first change after a quiet moment is evaluated at once, a follow-up within the debounce waits`() {
        val r = rc()
        r.onNetwork(7, NetKind.WIFI)
        assertEquals(0L, timer.delay) // a dozing phone must not depend on a timed wake-up
        timer.fire()
        now += 100
        r.onNetwork(8, NetKind.CELLULAR)
        assertEquals(PathRecheckPolicy.DEBOUNCE_MS, timer.delay)
        timer.fire()
        assertEquals(2, reconnects)
    }

    @Test fun `each decision is said to the connection log`() {
        val said = mutableListOf<String>()
        val r = PathRechecker(timer, { now }, { path }, { call }, { paused }, { reconnects++ }, { said.add(it) })
        r.onNetwork(99, NetKind.OTHER)
        r.onNetwork(7, NetKind.WIFI); timer.fire()
        paused = true
        r.onNetwork(8, NetKind.CELLULAR); timer.fire()
        assertEquals(listOf("recheck_recheck", "recheck_skip"), said)
    }
}

