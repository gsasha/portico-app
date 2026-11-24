package com.meda.portico.core.ring

import com.meda.portico.core.fcm.BootResult
import com.meda.portico.core.ha.PushCommands
import com.meda.portico.core.net.UrlCheck
import com.meda.portico.core.net.UrlPolicy
import com.meda.portico.core.net.UrlRefusal
import com.meda.portico.core.reg.RegStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** P2 plan §3.4 (permissions), §3.6 (push state, coexistence): what Setup shows in each state. */
class SetupStatusTest {
    private val healthy = PhoneState(
        notifPermission = NotifPermission.GRANTED, notificationsEnabled = true,
        ringChannel = ChannelState.OK, statusChannel = ChannelState.OK, ringBypassesDnd = true, fullScreenAllowed = true,
        dndActive = false, backgroundRestricted = false, ignoringBatteryOptimizations = true,
    )

    private fun keys(lines: List<StatusLine>) = lines.map { it.key }

    @Test
    fun `notification permission - not asked is not refused, and each has its own fix`() {
        assertEquals(NotifPermission.NOT_NEEDED, NotifPermission.of(32, granted = false, askedBefore = false, showRationale = false))
        assertEquals(NotifPermission.GRANTED, NotifPermission.of(33, granted = true, askedBefore = false, showRationale = false))
        assertEquals(NotifPermission.NOT_ASKED, NotifPermission.of(33, granted = false, askedBefore = false, showRationale = false))
        assertEquals(NotifPermission.DENIED_ASK_AGAIN, NotifPermission.of(34, granted = false, askedBefore = true, showRationale = true))
        assertEquals(NotifPermission.DENIED_PERMANENTLY, NotifPermission.of(34, granted = false, askedBefore = true, showRationale = false))

        fun first(p: NotifPermission) = SetupStatus.permissionLines(healthy.copy(notifPermission = p)).first()
        assertEquals(StatusLine(StatusKey.NOTIFICATIONS_NOT_ASKED, Severity.PROBLEM, Fix.REQUEST_NOTIFICATIONS), first(NotifPermission.NOT_ASKED))
        assertEquals(Fix.REQUEST_NOTIFICATIONS, first(NotifPermission.DENIED_ASK_AGAIN).fix)
        assertEquals(StatusLine(StatusKey.NOTIFICATIONS_DENIED_PERMANENT, Severity.PROBLEM), first(NotifPermission.DENIED_PERMANENTLY)) // no button: Settings > Android settings
        assertEquals(StatusKey.NOTIFICATIONS_OK, first(NotifPermission.NOT_NEEDED).key)
    }

    @Test
    fun `notifications switched off below Android 13 is still a problem`() {
        val l = SetupStatus.permissionLines(healthy.copy(notifPermission = NotifPermission.NOT_NEEDED, notificationsEnabled = false)).first()
        assertEquals(StatusLine(StatusKey.NOTIFICATIONS_OFF, Severity.PROBLEM), l)
    }

    @Test
    fun `full-screen intent - the real state on 14+, never assumed - below 14 not a runtime question`() {
        assertTrue(StatusKey.FULL_SCREEN_OK in keys(SetupStatus.permissionLines(healthy)))
        val denied = SetupStatus.permissionLines(healthy.copy(fullScreenAllowed = false)).single { it.key == StatusKey.FULL_SCREEN_DENIED }
        assertNull(denied.fix)
        assertTrue(StatusKey.FULL_SCREEN_NOT_A_QUESTION in keys(SetupStatus.permissionLines(healthy.copy(fullScreenAllowed = null))))
    }

    @Test
    fun `channels, DND, background restriction and battery guidance`() {
        val l = SetupStatus.permissionLines(
            healthy.copy(
                ringChannel = ChannelState.LOWERED, statusChannel = ChannelState.BLOCKED,
                dndActive = true, backgroundRestricted = true, ignoringBatteryOptimizations = false,
            ),
        )
        assertEquals(
            listOf(
                StatusKey.NOTIFICATIONS_OK, StatusKey.RING_CHANNEL_LOWERED, StatusKey.STATUS_CHANNEL_BLOCKED, StatusKey.RING_BYPASS_DND, StatusKey.FULL_SCREEN_OK,
                StatusKey.DND_ON, StatusKey.BACKGROUND_RESTRICTED, StatusKey.BATTERY_OPTIMISED,
            ),
            keys(l),
        )
        assertEquals(null, l[1].fix, "0.6.60: the ring channel's fix lives in Settings, not on Status")
        assertEquals(Severity.INFO, l.last().severity, "battery optimisation is guidance, not a fault: HIGH messages wake a dozing phone")
        assertEquals(StatusKey.RING_CHANNEL_BLOCKED, SetupStatus.permissionLines(healthy.copy(ringChannel = ChannelState.BLOCKED))[1].key)
        assertEquals(keys(SetupStatus.permissionLines(healthy)), keys(SetupStatus.permissionLines(healthy.copy(ringChannel = ChannelState.MISSING))))
    }

    @Test
    fun `ring channel and Do Not Disturb - the real state of the bypass, with the channel settings as the one fix (A-03)`() {
        val ok = SetupStatus.permissionLines(healthy).single { it.key == StatusKey.RING_BYPASS_DND }
        assertEquals(Severity.OK, ok.severity)
        val off = SetupStatus.permissionLines(healthy.copy(ringBypassesDnd = false)).single { it.key == StatusKey.RING_NO_BYPASS_DND }
        assertEquals(StatusLine(StatusKey.RING_NO_BYPASS_DND, Severity.INFO), off)
        assertTrue(StatusKey.RING_BYPASS_DND !in keys(SetupStatus.permissionLines(healthy.copy(ringBypassesDnd = false))))
        val blocked = keys(SetupStatus.permissionLines(healthy.copy(ringChannel = ChannelState.BLOCKED, ringBypassesDnd = false)))
        assertTrue(StatusKey.RING_NO_BYPASS_DND !in blocked && StatusKey.RING_BYPASS_DND !in blocked, "a blocked channel has its own line")
    }

    private fun row(enabled: Boolean, lastOk: String? = null, state: String? = "ok") =
        PushCommands.StatusRow("r1", "inst-1", "phone", "fid", state, enabled, null, lastOk, null, null, false)

    @Test
    fun `push - registered but off in HA is the normal first state (decision 4)`() {
        val l = SetupStatus.pushLines(BootResult.Ready("p"), LinkState.CONNECTED, RegStatus.Registered("r1", pushEnabled = false), null)
        assertEquals(listOf(StatusKey.LINK_CONNECTED, StatusKey.PUSH_OFF_IN_HA), keys(l))
        assertEquals(Severity.INFO, l[1].severity, "not a fault")
    }

    @Test
    fun `push - the status row is fresher than the register result`() {
        val reg = RegStatus.Registered("r1", pushEnabled = false)
        assertEquals(StatusKey.PUSH_ON, SetupStatus.pushLines(BootResult.Ready("p"), LinkState.CONNECTED, reg, row(true))[1].key)
        val last = SetupStatus.pushLines(BootResult.Ready("p"), LinkState.CONNECTED, reg, row(true, "2026-10-06T12:00:00+00:00"))[1]
        assertEquals(StatusLine(StatusKey.PUSH_ON_LAST_OK, Severity.OK, arg = "2026-10-06T12:00:00+00:00"), last)
        val mismatch = SetupStatus.pushLines(BootResult.Ready("p"), LinkState.CONNECTED, reg, row(true, state = "project_mismatch"))
        assertEquals(StatusKey.PUSH_SERVER_STATE, mismatch.last().key)
    }

    @Test
    fun `push - Firebase not configured, failed, or needing a restart`() {
        assertEquals(listOf(StatusKey.LINK_CONNECTED, StatusKey.PUSH_NO_FIREBASE), keys(SetupStatus.pushLines(BootResult.NotConfigured, LinkState.CONNECTED, null, null)))
        val failed = SetupStatus.pushLines(BootResult.Failed("IllegalStateException while starting Firebase"), LinkState.CONNECTING, null, null)
        assertEquals(StatusKey.PUSH_FIREBASE_FAILED, failed[1].key)
        val restart = SetupStatus.pushLines(BootResult.Ready("p", restartToApply = true), LinkState.CONNECTED, RegStatus.Sent, null)
        assertEquals(listOf(StatusKey.LINK_CONNECTED, StatusKey.PUSH_RESTART_TO_APPLY, StatusKey.PUSH_REGISTERING), keys(restart))
        assertEquals(Fix.RESTART_APP, restart[1].fix)
    }

    @Test
    fun `push - every registration status has a line`() {
        val statuses = listOf(
            null, RegStatus.NoFirebaseConfig, RegStatus.WaitingForRegistration, RegStatus.Ready, RegStatus.Sent,
            RegStatus.Registered("r", true), RegStatus.Refused("unauthorized", "m", "this phone is not enrolled"),
            RegStatus.ServerWithoutPush, RegStatus.RetryNextConnect("invalid_format"), RegStatus.PushOff, RegStatus.SignedOut,
        )
        for (s in statuses) assertEquals(2, SetupStatus.pushLines(BootResult.Ready("p"), LinkState.CONNECTED, s, null).size, "$s")
        val refused = SetupStatus.pushLines(BootResult.Ready("p"), LinkState.AUTH_REJECTED, statuses[6], null)
        assertEquals(StatusLine(StatusKey.LINK_AUTH_REJECTED, Severity.PROBLEM), refused[0])
        assertEquals("this phone is not enrolled", refused[1].arg)
    }

    @Test
    fun `coexistence - only the local switch is noted, no other app is detected`() {
        assertTrue(SetupStatus.coexistenceLines(ringOnThisPhone = true).isEmpty())
        assertEquals(listOf(StatusLine(StatusKey.RING_SWITCH_OFF, Severity.INFO)), SetupStatus.coexistenceLines(ringOnThisPhone = false))
    }

    @Test
    fun `PR-G - a refused CallStyle is shown, with the refusal, and nothing when it was accepted`() {
        assertTrue(SetupStatus.ringStyleLines(null).isEmpty())
        assertEquals(
            listOf(StatusLine(StatusKey.CALL_STYLE_REFUSED, Severity.INFO, arg = "IllegalArgumentException")),
            SetupStatus.ringStyleLines("IllegalArgumentException"),
        )
        // Only a short class name ever reaches the screen.
        assertEquals(64, SetupStatus.ringStyleLines("X".repeat(500)).single().arg!!.length)
        assertEquals(StatusLine(StatusKey.CALL_STYLE_REFUSED, Severity.INFO, arg = "unknown"), SetupStatus.ringStyleLines(" ").single())
    }

    @Test
    fun `own row is matched by installation id only`() {
        val status = PushCommands.Outcome.Status("ok", listOf(row(false).copy(installationId = "other"), row(true)))
        assertEquals(true, SetupStatus.ownRow(status, "inst-1")?.pushEnabled)
        assertNull(SetupStatus.ownRow(status, "nope"))
        assertNull(SetupStatus.ownRow(status, null))
        assertNull(SetupStatus.ownRow(PushCommands.Outcome.Lost, "inst-1"))
    }

    @Test
    fun `PR-J - plain http on the LAN is a persistent warning, a refused URL is a problem, https is silent`() {
        assertEquals(
            listOf(StatusLine(StatusKey.URL_PLAIN_HTTP, Severity.INFO)),
            SetupStatus.urlLines(UrlPolicy.check("http://homeassistant.local:8123")),
        )
        val refused = SetupStatus.urlLines(UrlPolicy.check("http://user:pw@ha.example.com"))
        assertEquals(listOf(StatusKey.URL_REFUSED), refused.map { it.key })
        assertEquals(Severity.PROBLEM, refused.single().severity)
        assertEquals(UrlRefusal.USERINFO.message, refused.single().arg)
        assertTrue("pw" !in refused.single().arg!! && "example" !in refused.single().arg!!)
        assertTrue(SetupStatus.urlLines(UrlCheck.Https("ha.example.com")).isEmpty())
        assertTrue(SetupStatus.urlLines(null).isEmpty())
    }

    @Test
    fun `PR-J - a link refused by the URL policy is a problem line`() {
        val lines = SetupStatus.pushLines(BootResult.NotConfigured, LinkState.URL_REFUSED, null, null)
        assertEquals(StatusLine(StatusKey.LINK_URL_REFUSED, Severity.PROBLEM), lines.first())
    }

    @Test
    fun `the Notifications button shows the system prompt first only while it can still be shown`() {
        assertTrue(NotifPermission.NOT_ASKED.promptsFirst)
        assertTrue(NotifPermission.DENIED_ASK_AGAIN.promptsFirst)
        assertTrue(!NotifPermission.GRANTED.promptsFirst && !NotifPermission.NOT_NEEDED.promptsFirst && !NotifPermission.DENIED_PERMANENTLY.promptsFirst)
        // a fresh Android 13+ install is "not asked", never "refused"
        assertTrue(NotifPermission.of(34, granted = false, askedBefore = false, showRationale = false).promptsFirst)
    }
}
