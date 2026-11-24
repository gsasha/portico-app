package com.meda.portico.store

import com.meda.portico.core.auth.AccessTokens
import com.meda.portico.core.auth.CompleteResult
import com.meda.portico.core.auth.EnrollContract
import com.meda.portico.core.enroll.SettingsKeys
import com.meda.portico.core.enroll.SetupPlan
import com.meda.portico.core.enroll.SetupResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DEV-544 on the real [Settings] class over a fake preference file: what a save writes (the golden for the OLD long-lived
 * token path), how a one-time code waits, what completes it, and what signs a phone out.
 */
class SettingsEnrollTest {
    private val prefs = FakeSharedPreferences()
    private val settings = Settings(prefs)
    private val token = "eyJSECRET.TOKEN.value-7f3a9c"
    private val enrollId = "ENROLLidFixture_0123456789-abcdefghijKLMNOP"
    private val code = "FIXTURE-code-0123456789abcdef-A"
    private val nonce = "n".repeat(48)

    private fun plan(text: String): SetupResult.Ok = SetupPlan.evaluate(text, "") as SetupResult.Ok
    private val legacyQr = """{"ha_url":"https://ha.example.net","ha_token":"$token","phone_name":"Pixel","enroll_id":"$enrollId"}"""
    private val codeQr = """{"portico":2,"ha_url":"https://ha.example.net","code":"$code","phone_name":"Pixel","enroll_id":"$enrollId"}"""

    private fun keys() = prefs.all.keys.toSet()
    private fun snapshot() = prefs.all.mapValues { it.value.toString() }

    // ---- the old path, golden -------------------------------------------------------------------

    @Test
    fun `GOLDEN - a format-1 save writes exactly these keys and no others`() {
        assertTrue(settings.apply(plan(legacyQr), nowMs = 1_700_000_000_000L, nonce = nonce))
        assertEquals(
            mapOf(
                "ha_url" to "https://ha.example.net",
                "ha_token" to token,
                "phone_name" to "Pixel",
                "enroll_id" to enrollId,
            ),
            snapshot(),
            "the keys a long-lived-token enrolment has always written, nothing added by DEV-544",
        )
        assertEquals(token, settings.haToken)
        assertNull(settings.refreshToken)
        assertNull(settings.clientId)
        assertNull(settings.pendingStore.load())
        assertTrue(settings.enrolled)
    }

    @Test
    fun `GOLDEN - a format-1 save with a tailnet key and dial writes the same extra keys as before`() {
        val qr = """{"ha_url":"https://ha.example.net","ha_token":"$token","phone_name":"P","tailscale_authkey":"tskey-auth-K","ha_dial":"192.168.1.5:443"}"""
        assertTrue(settings.apply(plan(qr), nowMs = 1_700_000_000_000L, nonce = nonce))
        assertEquals(
            setOf("ha_url", "ha_token", "phone_name", "ha_dial", "tailscale_authkey", "tailscale_authkey_saved_at"),
            keys(),
        )
    }

    @Test
    fun `GOLDEN - the legacy phone's connection config has no login and serves its stored token`() {
        settings.apply(plan(legacyQr), nowMs = 1L, nonce = nonce)
        val tokens = AccessTokens(settings.loginStore, com.meda.portico.core.auth.RefreshClient { _, _, _ -> throw AssertionError("no request for a legacy phone") }, { settings.haUrl }, { 0L })
        val cfg = SettingsConnectionConfig(tokens) { settings }
        assertNull(cfg.login, "no refresh token stored: the long-lived path is used")
        assertEquals(token, cfg.accessToken)
        assertEquals("https://ha.example.net", cfg.haUrl)
        assertFalse(cfg.toString().contains(token))
        // and with the default (no tokens object at all), as every existing caller builds it
        assertNull(SettingsConnectionConfig { settings }.login)
    }

    @Test
    fun `GOLDEN - a re-save of a format-1 QR over a format-1 phone replaces the token as it always did`() {
        settings.apply(plan(legacyQr), nowMs = 1L, nonce = nonce)
        settings.apply(plan(legacyQr.replace(token, "NEW-LONG-LIVED")), nowMs = 2L, nonce = nonce)
        assertEquals("NEW-LONG-LIVED", settings.haToken)
        assertNull(settings.pendingStore.load())
    }

    // ---- a one-time code waits ---------------------------------------------------------------------

    @Test
    fun `a format-2 save keeps the phone's working login and parks the code, encrypted store only`() {
        settings.apply(plan(legacyQr), nowMs = 1L, nonce = nonce)
        assertTrue(settings.apply(plan(codeQr), nowMs = 1_800_000_000_000L, nonce = nonce))
        assertEquals(token, settings.haToken, "the old login is untouched until the code is redeemed")
        assertNull(settings.refreshToken)
        val p = settings.pendingStore.load()!!
        assertEquals(code, p.code)
        assertEquals(nonce, p.nonce)
        assertEquals(enrollId, p.enrollId)
        assertEquals("https://ha.example.net", p.haUrl)
        assertEquals(1_800_000_000_000L, p.sinceMs)
        assertTrue(settings.snapshot().pendingRedemption)
        assertFalse(settings.snapshot().summaryLines().joinToString().contains(code))
    }

    @Test
    fun `a first-install phone that scanned a code is not enrolled until the redemption has finished`() {
        assertTrue(settings.apply(plan(codeQr), nowMs = 1L, nonce = nonce))
        assertFalse(settings.enrolled, "so the launcher still opens Setup")
        assertNull(settings.haToken)
        assertNull(SettingsConnectionConfig(null) { settings }.accessToken)
        assertNull(SettingsConnectionConfig(null) { settings }.login)
    }

    @Test
    fun `a new nonce is made for each save and is the contract's shape`() {
        settings.apply(plan(codeQr), nowMs = 1L)
        val first = settings.pendingStore.load()!!.nonce
        assertTrue(EnrollContract.isValidNonce(first))
        settings.apply(plan(codeQr), nowMs = 2L)
        val second = settings.pendingStore.load()!!.nonce
        assertTrue(EnrollContract.isValidNonce(second))
        assertTrue(first != second, "a new scan, a new nonce")
    }

    // ---- completion --------------------------------------------------------------------------------

    @Test
    fun `completing swaps the old token for the refresh token and drops the code in one commit`() {
        settings.apply(plan(legacyQr), nowMs = 1L, nonce = nonce)
        settings.apply(plan(codeQr), nowMs = 2L, nonce = nonce)
        assertEquals(CompleteResult.SAVED, settings.pendingStore.complete(nonce, "REFRESH-1", "https://portico.invalid/phone"))
        assertEquals("REFRESH-1", settings.refreshToken)
        assertEquals("https://portico.invalid/phone", settings.clientId)
        assertNull(settings.haToken, "a re-enrolment replaces the long-lived token")
        assertNull(settings.pendingStore.load())
        assertTrue(settings.enrolled)
        assertFalse(keys().any { it.startsWith("pending_") })
        val cfg = SettingsConnectionConfig(AccessTokens(settings.loginStore, com.meda.portico.core.auth.RefreshClient { _, _, _ -> throw AssertionError() }, { settings.haUrl }, { 0L })) { settings }
        assertNotNull(cfg.login)
        assertTrue(settings.snapshot().hasRefreshToken)
    }

    @Test
    fun `a commit that fails is reported as FAILED, so the redemption sends the same request again`() {
        settings.apply(plan(codeQr), nowMs = 2L, nonce = nonce)
        prefs.commitResult = false
        assertEquals(CompleteResult.FAILED, settings.pendingStore.complete(nonce, "R", "C"))
        prefs.commitResult = true
    }

    @Test
    fun `a newer scan supersedes - an older redemption can neither complete nor clear it`() {
        settings.apply(plan(codeQr), nowMs = 1L, nonce = "a".repeat(40))
        settings.apply(plan(codeQr), nowMs = 2L, nonce = "b".repeat(40))
        assertEquals(CompleteResult.SUPERSEDED, settings.pendingStore.complete("a".repeat(40), "R", "C"))
        settings.pendingStore.clear("a".repeat(40))
        assertEquals("b".repeat(40), settings.pendingStore.load()!!.nonce)
        assertNull(settings.refreshToken)
        settings.pendingStore.clear("b".repeat(40))
        assertNull(settings.pendingStore.load())
    }

    @Test
    fun `clearing the code leaves the working login and the address as they were`() {
        settings.apply(plan(legacyQr), nowMs = 1L, nonce = nonce)
        settings.apply(plan(codeQr), nowMs = 2L, nonce = nonce)
        settings.pendingStore.clear(nonce)
        assertEquals(token, settings.haToken)
        assertEquals("https://ha.example.net", settings.haUrl)
        assertEquals("Pixel", settings.phoneName)
    }

    // ---- sign-out ----------------------------------------------------------------------------------

    @Test
    fun `signing out forgets the login, marks the phone signed out and nothing else`() {
        settings.apply(plan(codeQr), nowMs = 2L, nonce = nonce)
        settings.pendingStore.complete(nonce, "R", "C")
        settings.loginStore.signOut()
        assertNull(settings.refreshToken)
        assertNull(settings.clientId)
        assertFalse(settings.enrolled)
        assertEquals("https://ha.example.net", settings.haUrl)
        assertTrue(settings.snapshot().signedOut)
        assertTrue(settings.snapshot().summaryLines().last().startsWith("Sign-in: signed out"))
        // enrolling again clears it
        settings.apply(plan(codeQr), nowMs = 3L, nonce = nonce)
        settings.pendingStore.complete(nonce, "R2", "C")
        assertFalse(settings.snapshot().signedOut)
        assertNull(prefs.getString(SettingsKeys.SIGNED_OUT, null))
    }

    @Test
    fun `the secrets are not in Settings' printable parts`() {
        settings.apply(plan(codeQr), nowMs = 2L, nonce = nonce)
        settings.pendingStore.complete(nonce, "REFRESH-SECRET", "C")
        for (s in listOf(settings.loginStore.toString(), settings.pendingStore.toString(), settings.snapshot().toString(), SettingsConnectionConfig(null) { settings }.toString())) {
            assertFalse(s.contains("REFRESH-SECRET") || s.contains(code) || s.contains(nonce), s)
        }
    }
}
