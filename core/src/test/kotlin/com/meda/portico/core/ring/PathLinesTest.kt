package com.meda.portico.core.ring

import com.meda.portico.core.ha.FailureClass
import com.meda.portico.core.ha.LinkFailure
import com.meda.portico.core.ha.LinkPath
import com.meda.portico.core.ha.PathAttempt
import com.meda.portico.core.tailnet.RouteCheck
import com.meda.portico.core.tailnet.TailnetState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Setup's per-path lines: which path was tried, when, why it failed, and the route to the dial IP. */
class PathLinesTest {
    private val now = 100_000L
    private val up = TailnetState.Up("100.70.97.30")
    private fun fail(path: LinkPath, cls: FailureClass, at: Long = 98_000, http: Int? = null) = PathAttempt(path, at, LinkFailure(cls, http))

    @Test
    fun `nothing tried yet - no lines`() {
        assertEquals(emptyList<StatusLine>(), SetupStatus.pathLines(null, null, TailnetState.Idle(false), RouteCheck.Unknown, "192.168.1.5", now))
    }

    @Test
    fun `the live case - direct name not resolved, tailnet dial timed out, no route`() {
        val lines = SetupStatus.pathLines(
            fail(LinkPath.DIRECT, FailureClass.NAME_NOT_RESOLVED, at = 70_000),
            fail(LinkPath.TAILNET, FailureClass.DIAL_TIMEOUT),
            up, RouteCheck.None("192.168.1.5", 4), "192.168.1.5", now,
        )
        assertEquals(
            listOf(
                StatusLine(StatusKey.PATH_DIRECT_FAILED, Severity.INFO, arg = "30 s ago", failure = FailureClass.NAME_NOT_RESOLVED),
                StatusLine(StatusKey.PATH_TAILNET_FAILED, Severity.PROBLEM, arg = "2 s ago", failure = FailureClass.DIAL_TIMEOUT),
                StatusLine(StatusKey.ROUTE_NONE, Severity.PROBLEM, arg = "192.168.1.5", arg2 = "4"),
            ),
            lines,
        )
    }

    @Test
    fun `tailnet opened - route via the router`() {
        val lines = SetupStatus.pathLines(
            fail(LinkPath.DIRECT, FailureClass.TIMED_OUT), PathAttempt(LinkPath.TAILNET, 99_000, null),
            up, RouteCheck.Via("example-router", "192.168.0.0/16"), "192.168.1.5", now,
        )
        assertEquals(StatusLine(StatusKey.PATH_TAILNET_OPENED, Severity.OK, arg = "1 s ago"), lines[1])
        assertEquals(StatusLine(StatusKey.ROUTE_VIA, Severity.OK, arg = "192.168.1.5", arg2 = "example-router (192.168.0.0/16)"), lines[2])
    }

    @Test
    fun `direct opened at home - OK, and no route line while the node sleeps`() {
        val lines = SetupStatus.pathLines(PathAttempt(LinkPath.DIRECT, now, null), null, TailnetState.Idle(true), RouteCheck.Unknown, "192.168.1.5", now)
        assertEquals(listOf(StatusLine(StatusKey.PATH_DIRECT_OPENED, Severity.OK, arg = "just now")), lines)
    }

    @Test
    fun `the field failure of 2026-10-08 - the forwarder's refusal names where it looked`() {
        val lines = SetupStatus.pathLines(
            fail(LinkPath.DIRECT, FailureClass.NAME_NOT_RESOLVED, at = 70_000),
            PathAttempt(LinkPath.TAILNET, 79_000, LinkFailure(FailureClass.FORWARDER_DOWN, endpoint = "127.0.0.1:41234")),
            up, RouteCheck.Via("example-nas", "192.168.0.0/16"), "192.168.1.5", now,
        )
        assertEquals(
            StatusLine(StatusKey.PATH_TAILNET_FAILED, Severity.PROBLEM, arg = "21 s ago", arg2 = "127.0.0.1:41234", failure = FailureClass.FORWARDER_DOWN),
            lines[1],
        )
    }

    @Test
    fun `an HTTP status carries its code`() {
        val lines = SetupStatus.pathLines(fail(LinkPath.DIRECT, FailureClass.HTTP_STATUS, http = 502), null, TailnetState.Idle(false), RouteCheck.Unknown, null, now)
        assertEquals(StatusLine(StatusKey.PATH_DIRECT_FAILED, Severity.PROBLEM, arg = "2 s ago", arg2 = "502", failure = FailureClass.HTTP_STATUS), lines.single())
    }

    @Test
    fun `a direct failure with no tailnet attempt is the problem`() {
        val l = SetupStatus.pathLines(fail(LinkPath.DIRECT, FailureClass.REFUSED), null, TailnetState.Idle(false), RouteCheck.Unknown, "192.168.1.5", now)
        assertEquals(Severity.PROBLEM, l.single().severity)
    }

    @Test
    fun `not built - says so plainly after a direct failure, no route line`() {
        val l = SetupStatus.pathLines(fail(LinkPath.DIRECT, FailureClass.NAME_NOT_RESOLVED), null, TailnetState.NotBuilt, RouteCheck.Unknown, "192.168.1.5", now)
        assertEquals(listOf(StatusKey.PATH_DIRECT_FAILED, StatusKey.PATH_TAILNET_NOT_BUILT), l.map { it.key })
        assertEquals(Severity.PROBLEM, l[0].severity)
        val ok = SetupStatus.pathLines(PathAttempt(LinkPath.DIRECT, now, null), null, TailnetState.NotBuilt, RouteCheck.Unknown, "192.168.1.5", now)
        assertEquals(listOf(StatusKey.PATH_DIRECT_OPENED), ok.map { it.key })
    }

    @Test
    fun `route unknown while up, and no route line for a name target`() {
        val l = SetupStatus.pathLines(null, null, up, RouteCheck.Unknown, "192.168.1.5", now)
        assertEquals(listOf(StatusLine(StatusKey.ROUTE_UNKNOWN, Severity.INFO, arg = "192.168.1.5")), l)
        val notIp = SetupStatus.pathLines(null, null, up, RouteCheck.NotIp, "192.168.1.5", now)
        assertEquals(StatusKey.ROUTE_UNKNOWN, notIp.single().key)
        assertTrue(SetupStatus.pathLines(null, fail(LinkPath.TAILNET, FailureClass.NAME_NOT_RESOLVED), up, RouteCheck.NotIp, null, now).none { it.key.name.startsWith("ROUTE") })
    }

    @Test
    fun `a tailnet attempt while the node is down still shows the route (unknown)`() {
        val l = SetupStatus.pathLines(null, fail(LinkPath.TAILNET, FailureClass.TAILNET_NOT_UP), TailnetState.Joining(), RouteCheck.Unknown, "192.168.1.5", now)
        assertEquals(listOf(StatusKey.PATH_TAILNET_FAILED, StatusKey.ROUTE_UNKNOWN), l.map { it.key })
    }
}

/** DEV-694 P0: the one line with the last connect's timings. */
class ConnectTimelineLineTest {
    private fun snap(vararg m: Pair<com.meda.portico.core.ha.ConnectTimeline.Stage, Long>) =
        com.meda.portico.core.ha.ConnectTimeline.Snapshot(0, m.map { com.meda.portico.core.ha.ConnectTimeline.Mark(it.first, it.second) })

    @Test
    fun `a recorded connect adds one INFO line after the path lines`() {
        val s = snap(com.meda.portico.core.ha.ConnectTimeline.Stage.DIRECT_FAILED to 120, com.meda.portico.core.ha.ConnectTimeline.Stage.NODE_UP to 4_300)
        val lines = SetupStatus.pathLines(
            PathAttempt(LinkPath.DIRECT, 100_000, null), null, TailnetState.Idle(true), RouteCheck.Unknown, "192.168.1.5", 100_000, timeline = s,
        )
        assertEquals(StatusLine(StatusKey.CONNECT_TIMELINE, Severity.INFO, arg = "direct failed +120 ms, node up +4.3 s"), lines.last())
        assertEquals(2, lines.size)
    }

    @Test
    fun `no timeline, an empty one, or a paused phone adds nothing`() {
        assertEquals(emptyList<StatusLine>(), SetupStatus.timelineLines(null))
        assertEquals(emptyList<StatusLine>(), SetupStatus.timelineLines(snap()))
        assertEquals(emptyList<StatusLine>(), SetupStatus.timelineLines(snap(com.meda.portico.core.ha.ConnectTimeline.Stage.NODE_UP to 5), paused = true))
    }
}
