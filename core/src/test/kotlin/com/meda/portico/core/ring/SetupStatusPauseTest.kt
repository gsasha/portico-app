package com.meda.portico.core.ring

import com.meda.portico.core.fcm.BootResult
import com.meda.portico.core.ha.FailureClass
import com.meda.portico.core.ha.LinkFailure
import com.meda.portico.core.ha.LinkPath
import com.meda.portico.core.ha.PathAttempt
import com.meda.portico.core.ha.PushCommands
import com.meda.portico.core.pause.PauseRules
import com.meda.portico.core.pause.PauseState
import com.meda.portico.core.reg.OfflineInputs
import com.meda.portico.core.reg.OfflineOffer
import com.meda.portico.core.reg.OfflineRegistration
import com.meda.portico.core.reg.RegKind
import com.meda.portico.core.reg.RegStatus
import com.meda.portico.core.reg.RegValue
import com.meda.portico.core.reg.RegistrationConfig
import com.meda.portico.core.tailnet.FailReason
import com.meda.portico.core.tailnet.RouteCheck
import com.meda.portico.core.tailnet.TailnetState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A paused phone is not a broken one: Setup's connection, push and tailnet lines say "paused", never a failure. */
class SetupStatusPauseTest {
    private val ready = BootResult.Ready("portico-test")
    private val now = 1_760_000_000_000L

    private fun problems(lines: List<StatusLine>) = lines.filter { it.severity == Severity.PROBLEM }

    @Test
    fun `the socket's PAUSED state is its own INFO line, not stopped and not a failure`() {
        val lines = SetupStatus.pushLines(ready, LinkState.PAUSED, RegStatus.RetryNextConnect("connection lost"), null)
        assertEquals(StatusLine(StatusKey.LINK_PAUSED, Severity.INFO), lines.first())
        assertTrue(problems(lines).isEmpty(), "$lines")
    }

    @Test
    fun `while paused the registration line says HA keeps it, whatever the machine last said`() {
        for (reg in listOf(null, RegStatus.RetryNextConnect("x"), RegStatus.Registered("r1", pushEnabled = true), RegStatus.Refused("bad", "x", "x"))) {
            val lines = SetupStatus.pushLines(ready, LinkState.PAUSED, reg, null)
            assertEquals(listOf(StatusKey.LINK_PAUSED, StatusKey.PUSH_PAUSED), lines.map { it.key }, "reg=$reg")
        }
    }

    @Test
    fun `while paused a stale server state is not a problem either`() {
        val row = PushCommands.StatusRow(
            regId = "r1", installationId = "i", name = null, kind = null, state = "unregistered", pushEnabled = true,
            lastResult = null, lastOkAt = null, lastSeenAt = null, registrationUpdatedAt = null, stale = false,
        )
        assertTrue(problems(SetupStatus.pushLines(ready, LinkState.PAUSED, null, row)).isEmpty())
    }

    @Test
    fun `while paused a real Firebase configuration problem is still shown`() {
        val lines = SetupStatus.pushLines(BootResult.Failed("bad options"), LinkState.PAUSED, null, null)
        assertEquals(listOf(StatusKey.LINK_PAUSED, StatusKey.PUSH_FIREBASE_FAILED), lines.map { it.key })
    }

    @Test
    fun `not paused - the old lines, unchanged`() {
        val lines = SetupStatus.pushLines(ready, LinkState.WAITING_TO_RETRY, RegStatus.RetryNextConnect("x"), null)
        assertEquals(listOf(StatusKey.LINK_RETRYING, StatusKey.PUSH_RETRY), lines.map { it.key })
    }

    @Test
    fun `tailnet while paused - one INFO line, whatever the node last did`() {
        for (st in listOf(TailnetState.Failed(FailReason.TIMEOUT), TailnetState.Joining(needsApproval = true), TailnetState.Idle(hasIdentity = true), TailnetState.Up("100.64.0.7"))) {
            assertEquals(listOf(StatusLine(StatusKey.TAILNET_PAUSED, Severity.INFO)), SetupStatus.tailnetLines(st, LinkPath.TAILNET, paused = true), "$st")
        }
    }

    @Test
    fun `tailnet not built stays not built while paused`() {
        assertEquals(listOf(StatusKey.TAILNET_NOT_BUILT), SetupStatus.tailnetLines(TailnetState.NotBuilt, null, paused = true).map { it.key })
    }

    @Test
    fun `the per-path lines are history while paused - none shown, so no stale failure`() {
        val failed = PathAttempt(LinkPath.DIRECT, now - 60_000, LinkFailure(FailureClass.TIMED_OUT))
        val tailnet = PathAttempt(LinkPath.TAILNET, now - 50_000, LinkFailure(FailureClass.TAILNET_NOT_UP))
        val st = TailnetState.Failed(FailReason.TIMEOUT)
        assertTrue(SetupStatus.pathLines(failed, tailnet, st, RouteCheck.Unknown, "192.168.1.5", now, paused = true).isEmpty())
        assertTrue(problems(SetupStatus.pathLines(failed, tailnet, st, RouteCheck.Unknown, "192.168.1.5", now)).isNotEmpty(), "unpaused, the failure shows")
    }

    @Test
    fun `the pause line - until HH_MM for a timed pause, until resumed otherwise, nothing when not paused`() {
        val hhmm: (Long) -> String = { "14:30" }
        assertEquals(listOf(StatusLine(StatusKey.PAUSED_UNTIL, Severity.INFO, arg = "14:30")), SetupStatus.pauseLines(PauseRules.start(now, PauseRules.ONE_HOUR), now, hhmm))
        assertEquals(listOf(StatusLine(StatusKey.PAUSED_UNTIL_RESUMED, Severity.INFO)), SetupStatus.pauseLines(PauseRules.start(now, PauseRules.UNTIL_RESUMED), now, hhmm))
        assertTrue(SetupStatus.pauseLines(PauseState.NOT_PAUSED, now, hhmm).isEmpty())
        assertTrue(SetupStatus.pauseLines(PauseRules.start(now, PauseRules.ONE_HOUR), now + PauseRules.HOUR, hhmm).isEmpty(), "expired")
    }

    @Test
    fun `offline registration is hidden while paused (a paused phone is not one that cannot reach HA)`() {
        val i = OfflineInputs(
            configured = true, link = LinkState.PAUSED, notConnectedForMs = 3_600_000,
            regStatus = RegStatus.RetryNextConnect("x"), value = RegValue(RegKind.FID, "cPYZbX0nQ0mJ9mVdK3x_-A"),
            enrollId = "enr-1", config = RegistrationConfig("inst", "portico-test", "0.1.0"),
        )
        assertEquals(OfflineOffer.Hidden, OfflineRegistration.decide(i))
    }
}
