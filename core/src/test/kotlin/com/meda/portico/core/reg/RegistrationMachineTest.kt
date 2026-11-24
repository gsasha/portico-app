package com.meda.portico.core.reg

import com.meda.portico.core.ha.Clock
import com.meda.portico.core.ha.PushCommands.ErrorKind
import com.meda.portico.core.ha.PushCommands.Outcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RegistrationMachineTest {
    private val cfg = RegistrationConfig("inst-1", "portico-test", "0.1.0")
    private val fid = RegValue(RegKind.FID, "fid-AAAAAAAAAAAAAAAAAAAAAA")
    private val fid2 = RegValue(RegKind.FID, "fid-BBBBBBBBBBBBBBBBBBBBBB")
    private val token = RegValue(RegKind.TOKEN, "token-CCCCCCCCCCCCCCCCCCCCCCCC")
    private var now = 1_000_000L
    private val store = InMemoryRegistrationStore()
    private fun machine(config: RegistrationConfig = cfg, enabled: Boolean = true) =
        RegistrationMachine(config, store, Clock { now }, pushEnabled = enabled)

    private fun ok(enabled: Boolean = false) = Outcome.Registered("reg-1", enabled)
    private fun fail(code: String) = Outcome.Failed(ErrorKind.of(code), code, "server says $code")
    private fun sends(effects: List<RegEffect>) = effects.filterIsInstance<RegEffect.SendRegister>().map { it.value }

    /** A machine that is connected and registered with [fid]. */
    private fun registered(): RegistrationMachine {
        store.value = fid
        val m = machine()
        assertEquals(listOf(fid), sends(m.onConnected()))
        m.onRegisterResult(ok())
        return m
    }

    @Test
    fun `no fcm block - never registers`() {
        store.value = fid
        val m = machine(cfg.copy(fcmProjectId = null))
        assertEquals(RegStatus.NoFirebaseConfig, m.status)
        assertEquals(emptyList<RegEffect>(), m.start())
        assertEquals(emptyList<RegEffect>(), m.onConnected())
        assertEquals(emptyList<RegEffect>(), m.onRegistrationValue(fid2))
        assertEquals(RegStatus.NoFirebaseConfig, m.status)
    }

    @Test
    fun `start asks Firebase for the FID`() {
        val m = machine()
        assertEquals(listOf(RegEffect.RequestFid), m.start())
        assertEquals(RegStatus.WaitingForRegistration, m.status)
        assertEquals(emptyList<RegEffect>(), m.start(), "one request in flight at a time")
    }

    @Test
    fun `connect with no value waits - the FID then registers`() {
        val m = machine()
        m.start()
        assertTrue(sends(m.onConnected()).isEmpty())
        assertEquals(RegStatus.WaitingForRegistration, m.status)
        val e = m.onRegistrationValue(fid)
        assertEquals(listOf(fid), sends(e))
        assertEquals("inst-1", (e.single() as RegEffect.SendRegister).config.installationId)
        assertEquals(RegStatus.Sent, m.status)
        assertEquals(fid, store.value, "cached for the next connect")
        m.onRegisterResult(ok(true))
        assertEquals(RegStatus.Registered("reg-1", true), m.status)
    }

    @Test
    fun `a value with no connection is Ready and is sent on connect`() {
        val m = machine()
        assertTrue(m.onRegistrationValue(fid).isEmpty())
        assertEquals(RegStatus.Ready, m.status)
        assertEquals(listOf(fid), sends(m.onConnected()))
    }

    @Test
    fun `register on EVERY connect - three connects, three registers`() {
        store.value = fid
        val m = machine()
        repeat(3) {
            assertEquals(listOf(fid), sends(m.onConnected()), "connect #${it + 1}")
            m.onRegisterResult(ok())
            m.onDisconnected()
        }
    }

    @Test
    fun `rotation while connected re-registers with the new value`() {
        val m = registered()
        assertEquals(listOf(fid2), sends(m.onRegistrationValue(fid2)))
        assertEquals(fid2, store.value)
        m.onRegisterResult(ok(true))
        assertEquals(RegStatus.Registered("reg-1", true), m.status)
    }

    @Test
    fun `the same value again is a no-op (idempotent)`() {
        val m = registered()
        assertEquals(emptyList<RegEffect>(), m.onRegistrationValue(fid))
        assertEquals(emptyList<RegEffect>(), m.onRegistrationValue(RegValue(RegKind.FID, fid.value)))
    }

    @Test
    fun `rotation during an in-flight register is sent after its result`() {
        store.value = fid
        val m = machine()
        m.onConnected()
        assertEquals(emptyList<RegEffect>(), m.onRegistrationValue(fid2), "one register in flight at a time")
        assertEquals(listOf(fid2), sends(m.onRegisterResult(ok())))
        assertEquals(RegStatus.Sent, m.status)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "  ", "unknown", "None", "undefined"])
    fun `a placeholder value is never sent or cached`(bad: String) {
        val m = registered()
        assertEquals(emptyList<RegEffect>(), m.onRegistrationValue(RegValue(RegKind.FID, bad)))
        assertEquals(fid, store.value)
    }

    @Test
    fun `a corrupt cached placeholder is dropped on connect and the FID re-requested`() {
        store.value = RegValue(RegKind.FID, "unknown")
        val m = machine()
        val e = m.onConnected()
        assertTrue(sends(e).isEmpty())
        assertTrue(RegEffect.RequestFid in e)
        assertNull(store.value)
    }

    @Test
    fun `token fallback after 30 s without an FID`() {
        val m = machine()
        m.start()
        now += RegistrationMachine.TOKEN_FALLBACK_MS - 1
        assertEquals(emptyList<RegEffect>(), m.tick())
        now += 1
        assertEquals(listOf(RegEffect.RequestToken), m.tick())
        assertEquals(emptyList<RegEffect>(), m.tick(), "asked once")
        m.onConnected()
        assertEquals(listOf(token), sends(m.onRegistrationValue(token)))
    }

    @Test
    fun `token fallback at once when the FID request fails`() {
        val m = machine()
        m.start()
        assertEquals(listOf(RegEffect.RequestToken), m.onRegistrationSourceFailed())
    }

    @Test
    fun `an FID that arrives in time cancels the token fallback`() {
        val m = machine()
        m.start()
        m.onRegistrationValue(fid)
        now += 60_000
        assertEquals(emptyList<RegEffect>(), m.tick())
    }

    @Test
    fun `FID beats token - a late token never replaces a cached FID, an FID replaces a token`() {
        val m = registered()
        assertEquals(emptyList<RegEffect>(), m.onRegistrationValue(token))
        assertEquals(fid, store.value)
        store.value = token
        val m2 = machine()
        m2.onConnected()
        m2.onRegisterResult(ok())
        assertEquals(listOf(fid), sends(m2.onRegistrationValue(fid)))
    }

    @Test
    fun `a token is used when there is no FID`() {
        val m = machine()
        m.onConnected()
        assertEquals(listOf(token), sends(m.onRegistrationValue(token)))
        assertEquals(token, store.value)
    }

    // ---- the error table (plan §3.2) ------------------------------------------------------

    @Test
    fun `unauthorized - stop until re-enrolled`() {
        store.value = fid
        val m = machine()
        m.onConnected()
        m.onRegisterResult(fail("unauthorized"))
        val s = m.status as RegStatus.Refused
        assertEquals("unauthorized", s.code)
        assertTrue(s.userText.contains("not enrolled"))
        m.onDisconnected()
        assertTrue(sends(m.onConnected()).isEmpty(), "terminal across connects")
        assertTrue(sends(m.onRegistrationValue(fid2)).isEmpty(), "terminal across rotation")
        assertEquals(listOf(fid2), sends(m.onConfigChanged(cfg)), "a Setup save (re-enrolment) lifts it")
    }

    @Test
    fun `project_mismatch - show the server's message, stop until the fcm block changes`() {
        store.value = fid
        val m = machine()
        m.onConnected()
        m.onRegisterResult(fail("project_mismatch"))
        val s = m.status as RegStatus.Refused
        assertEquals("server says project_mismatch", s.userText)
        m.onDisconnected()
        assertTrue(sends(m.onConnected()).isEmpty())
        assertTrue(sends(m.onConfigChanged(cfg)).isEmpty(), "the same project is not a fix")
        assertEquals(listOf(fid), sends(m.onConfigChanged(cfg.copy(fcmProjectId = "portico-real"))))
    }

    @Test
    fun `package_mismatch - an app bug, stop`() {
        store.value = fid
        val m = machine()
        m.onConnected()
        m.onRegisterResult(fail("package_mismatch"))
        assertEquals("package_mismatch", (m.status as RegStatus.Refused).code)
        m.onDisconnected()
        assertTrue(sends(m.onConnected()).isEmpty())
    }

    @Test
    fun `invalid_registration - drop the cached value, ask again once, then stop`() {
        store.value = fid
        val m = machine()
        m.onConnected()
        val e = m.onRegisterResult(fail("invalid_registration"))
        assertEquals(listOf(RegEffect.RequestFid), e)
        assertNull(store.value)
        assertEquals(RegStatus.WaitingForRegistration, m.status)
        assertEquals(listOf(fid2), sends(m.onRegistrationValue(fid2)))
        assertTrue(m.onRegisterResult(fail("invalid_registration")).none { it == RegEffect.RequestFid })
        assertEquals("invalid_registration", (m.status as RegStatus.Refused).code)
        m.onDisconnected()
        assertTrue(sends(m.onConnected()).isEmpty())
    }

    @Test
    fun `invalid_registration's one retry is re-armed by a success`() {
        store.value = fid
        val m = machine()
        m.onConnected()
        m.onRegisterResult(fail("invalid_registration"))
        m.onRegistrationValue(fid2)
        m.onRegisterResult(ok())
        val fid3 = RegValue(RegKind.FID, "fid-DDDDDDDDDDDDDDDDDDDDDD")
        assertEquals(listOf(fid3), sends(m.onRegistrationValue(fid3)))
        assertEquals(listOf(RegEffect.RequestFid), m.onRegisterResult(fail("invalid_registration")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["push_not_configured", "unknown_command"])
    fun `server without push - quiet status, no retry this connection, retry next connect`(code: String) {
        store.value = fid
        val m = machine()
        m.onConnected()
        m.onRegisterResult(fail(code))
        assertEquals(RegStatus.ServerWithoutPush, m.status)
        assertTrue(sends(m.onRegistrationValue(fid2)).isEmpty(), "no retry on this connection")
        m.onDisconnected()
        assertEquals(listOf(fid2), sends(m.onConnected()))
    }

    @ParameterizedTest
    @ValueSource(strings = ["invalid_format", "home_assistant_error", "unknown_error"])
    fun `transient errors - one attempt per connect, retried next connect`(code: String) {
        store.value = fid
        val m = machine()
        m.onConnected()
        assertTrue(m.onRegisterResult(fail(code)).isEmpty(), "no tight loop")
        assertTrue(m.status is RegStatus.RetryNextConnect)
        assertTrue(m.tick().isEmpty())
        m.onDisconnected()
        assertEquals(listOf(fid), sends(m.onConnected()))
    }

    @Test
    fun `a register lost to a dropped socket is retried on the next connect`() {
        store.value = fid
        val m = machine()
        m.onConnected()
        m.onDisconnected()
        assertTrue(m.status is RegStatus.RetryNextConnect || m.status == RegStatus.Ready)
        assertEquals(listOf(fid), sends(m.onConnected()))
        m.onRegisterResult(Outcome.Lost)
        assertTrue(m.status is RegStatus.RetryNextConnect)
    }

    @Test
    fun `a new value after a transient error is worth one send`() {
        store.value = fid
        val m = machine()
        m.onConnected()
        m.onRegisterResult(fail("invalid_format"))
        assertEquals(listOf(fid2), sends(m.onRegistrationValue(fid2)))
    }

    // ---- unregister ------------------------------------------------------------------------

    @Test
    fun `push off while connected unregisters - not_found counts as success`() {
        val m = registered()
        assertEquals(listOf(RegEffect.SendUnregister("inst-1")), m.setPushEnabled(false))
        assertEquals(RegStatus.PushOff, m.status)
        m.onUnregisterResult(Outcome.Unregistered(removed = false))
        assertFalse(store.pendingUnregister)
        assertEquals(RegStatus.PushOff, m.status)
        assertTrue(sends(m.onRegistrationValue(fid2)).isEmpty(), "push off: no register on rotation")
        m.onDisconnected()
        assertEquals(emptyList<RegEffect>(), m.onConnected(), "push off: no register on connect")
    }

    @Test
    fun `push off while offline is queued and sent on the next connect instead of register`() {
        store.value = fid
        val m = machine()
        assertTrue(m.setPushEnabled(false).isEmpty())
        assertTrue(store.pendingUnregister)
        assertEquals(listOf(RegEffect.SendUnregister("inst-1")), machine(enabled = false).onConnected(), "survives a process death")
        val m2 = machine(enabled = false)
        m2.onConnected()
        m2.onUnregisterResult(Outcome.Unregistered(true))
        assertFalse(store.pendingUnregister)
    }

    @Test
    fun `a failed unregister stays pending for the next connect`() {
        val m = registered()
        m.setPushEnabled(false)
        m.onUnregisterResult(fail("invalid_format"))
        assertTrue(store.pendingUnregister)
        m.onDisconnected()
        assertEquals(listOf(RegEffect.SendUnregister("inst-1")), m.onConnected())
        m.onUnregisterResult(Outcome.Lost)
        assertTrue(store.pendingUnregister)
    }

    @Test
    fun `unregister refused as push_not_configured or unauthorized has nothing left to remove`() {
        for (code in listOf("push_not_configured", "unauthorized")) {
            val m = registered()
            m.setPushEnabled(false)
            m.onUnregisterResult(fail(code))
            assertFalse(store.pendingUnregister, code)
        }
    }

    @Test
    fun `push back on cancels a pending unregister and registers`() {
        val m = registered()
        m.setPushEnabled(false)
        m.onUnregisterResult(Outcome.Unregistered(true))
        assertEquals(listOf(fid), sends(m.setPushEnabled(true)))
        assertFalse(store.pendingUnregister)
    }

    @Test
    fun `sign-out unregisters if connected, forgets the value, and never registers again`() {
        val m = registered()
        assertEquals(listOf(RegEffect.SendUnregister("inst-1")), m.signOut())
        assertEquals(RegStatus.SignedOut, m.status)
        assertNull(store.value)
        m.onUnregisterResult(Outcome.Unregistered(true))
        assertEquals(RegStatus.SignedOut, m.status)
        m.onDisconnected()
        assertEquals(emptyList<RegEffect>(), m.onConnected())
        assertEquals(emptyList<RegEffect>(), m.onRegistrationValue(fid2))
    }

    @Test
    fun `sign-out while offline queues nothing (HA's user_removed covers the row)`() {
        store.value = fid
        val m = machine()
        assertEquals(emptyList<RegEffect>(), m.signOut())
        assertFalse(store.pendingUnregister)
    }

    @Test
    fun `toString of effects and values never shows the registration`() {
        val e = RegEffect.SendRegister(fid, cfg)
        assertFalse(e.toString().contains(fid.value))
        assertFalse(fid.toString().contains(fid.value))
        assertFalse(RegStatus.Registered("reg-1", true).toString().contains(fid.value))
    }
}
