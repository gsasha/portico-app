package com.meda.portico.core.enroll

import com.meda.portico.core.auth.EnrollFixture
import com.meda.portico.core.auth.PendingCodec
import com.meda.portico.core.enroll.Fixtures.ENROLL_ID
import com.meda.portico.core.enroll.Fixtures.TOKEN
import com.meda.portico.core.enroll.Fixtures.TS_KEY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** DEV-544: the format-2 QR (a one-time code, no token), and the proof that a format-1 payload is read exactly as before. */
class EnrollmentCodeTest {
    private val code = EnrollFixture.values.getValue("code")
    private val qr = """{"portico":2,"ha_url":"https://ha.example.net","code":"$code","phone_name":"Pixel","enroll_id":"$ENROLL_ID"}"""

    private fun ok(text: String): EnrollmentPayload = when (val r = EnrollmentPayload.parse(text)) {
        is EnrollmentParseResult.Ok -> r.payload
        is EnrollmentParseResult.Refused -> fail("expected Ok, got ${r.reason}")
    }

    private fun refusal(text: String): EnrollmentRefusal = when (val r = EnrollmentPayload.parse(text)) {
        is EnrollmentParseResult.Ok -> fail("expected Refused, got Ok")
        is EnrollmentParseResult.Refused -> r.reason
    }

    @Test
    fun `a format-2 QR is read - the code, no token`() {
        val p = ok(qr)
        assertEquals(2, p.formatVersion)
        assertEquals(code, p.code)
        assertNull(p.haToken)
        assertEquals(ENROLL_ID, p.enrollId)
        assertEquals("Pixel", p.phoneName)
        assertEquals("ha.example.net", p.haHost)
    }

    @Test
    fun `a format-2 QR carries the optional parts as format 1 did`() {
        val p = ok("""{"portico":2,"ha_url":"https://ha.example.net","code":"$code","phone_name":"P","enroll_id":"$ENROLL_ID","tailscale_authkey":"$TS_KEY","ha_dial":"192.168.1.5:443"}""")
        assertEquals(TS_KEY, p.tailscaleAuthKey)
        assertEquals("192.168.1.5:443", p.haDial)
    }

    @Test
    fun `a format-2 QR that carries ha_token is refused whole, even with a good code`() {
        assertEquals(EnrollmentRefusal.CODE_QR_WITH_TOKEN, refusal(qr.replace("{", "{\"ha_token\":\"$TOKEN\",")))
        assertEquals(EnrollmentRefusal.CODE_QR_WITH_TOKEN, refusal(qr.replace("{", "{\"ha_token\":\"\",")))
        assertEquals(EnrollmentRefusal.CODE_QR_WITH_TOKEN, refusal(qr.replace("{", "{\"ha_token\":null,")))
    }

    @Test
    fun `a format-2 QR with no code, an empty one or a malformed one is refused`() {
        assertEquals(EnrollmentRefusal.MISSING_CODE, refusal(qr.replace("\"code\":\"$code\",", "")))
        assertEquals(EnrollmentRefusal.MISSING_CODE, refusal(qr.replace(code, "")))
        assertEquals(EnrollmentRefusal.INVALID_CODE, refusal(qr.replace("\"$code\"", "123")))
        assertEquals(EnrollmentRefusal.INVALID_CODE, refusal(qr.replace("\"$code\"", "null")))
        assertEquals(EnrollmentRefusal.INVALID_CODE, refusal(qr.replace(code, "short")))
        assertEquals(EnrollmentRefusal.INVALID_CODE, refusal(qr.replace(code, "a".repeat(129))))
        assertEquals(EnrollmentRefusal.INVALID_CODE, refusal(qr.replace(code, "has space in it 0123456789")))
        assertEquals(EnrollmentRefusal.INVALID_CODE, refusal(qr.replace(code, "x".repeat(21) + "!")))
        assertEquals(EnrollmentRefusal.INVALID_CODE, refusal(qr.replace(code, " $code")), "no trimming of a credential")
    }

    @Test
    fun `a format-2 QR needs an enroll_id, a valid one`() {
        assertEquals(EnrollmentRefusal.MISSING_ENROLL_ID, refusal(qr.replace(",\"enroll_id\":\"$ENROLL_ID\"", "")))
        assertEquals(EnrollmentRefusal.MISSING_ENROLL_ID, refusal(qr.replace("\"$ENROLL_ID\"", "null")))
        assertEquals(EnrollmentRefusal.INVALID_ENROLL_ID, refusal(qr.replace(ENROLL_ID, "short")))
    }

    @Test
    fun `an app that does not know the format number refuses with update-the-app and saves nothing`() {
        for (m in listOf("3", "4", "0", "\"2\"", "2.5", "true")) {
            assertEquals(EnrollmentRefusal.UNSUPPORTED_FORMAT, refusal(qr.replace("\"portico\":2", "\"portico\":$m")), m)
        }
        assertTrue(EnrollmentRefusal.UNSUPPORTED_FORMAT.message.contains("Update the app"))
        val r = SetupPlan.evaluate(qr.replace("\"portico\":2", "\"portico\":3"), "")
        assertTrue(r is SetupResult.Refused)
    }

    @Test
    fun `a code with no marker is not a format-2 QR`() {
        assertEquals(EnrollmentRefusal.MISSING_HA_TOKEN, refusal(qr.replace("\"portico\":2,", "")))
    }

    @Test
    fun `format 1 is still accepted exactly as before - marker 1, and no marker`() {
        val a = ok("""{"portico":1,"ha_url":"https://ha.example.net","ha_token":"$TOKEN","phone_name":"Pixel","enroll_id":"$ENROLL_ID"}""")
        assertEquals(1, a.formatVersion)
        assertEquals(TOKEN, a.haToken)
        assertNull(a.code)
        val b = ok(Fixtures.ENROLLMENT_TODAY)
        assertNull(b.formatVersion)
        assertEquals(TOKEN, b.haToken)
        assertNull(b.code)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"portico\":1,", ""])
    fun `a format-1 payload that also carries a code ignores the code`(marker: String) {
        val p = ok("""{$marker"ha_url":"https://h","ha_token":"t0k","phone_name":"p","code":"$code"}""")
        assertNull(p.code)
        assertEquals("t0k", p.haToken)
    }

    @Test
    fun `format-1 writes are exactly the keys they always were (golden)`() {
        val r = SetupPlan.evaluate(Fixtures.ENROLLMENT_WITH_ENROLL_ID, "") as SetupResult.Ok
        assertEquals(
            mapOf(
                SettingsKeys.HA_URL to "https://ha.example.net",
                SettingsKeys.HA_TOKEN to TOKEN,
                SettingsKeys.PHONE_NAME to "Pixel",
                SettingsKeys.ENROLL_ID to ENROLL_ID,
            ),
            r.writes(),
        )
        // SEC-03: a format-1 save replaces any previous format-2 login, so those keys go (the enroll_id stays: this payload has one)
        assertEquals(setOf(SettingsKeys.REFRESH_TOKEN, SettingsKeys.CLIENT_ID, SettingsKeys.SIGNED_OUT) + PendingCodec.keys, r.removals())
        assertTrue(r.pendingWrites("n".repeat(48), 1L).isEmpty(), "a format-1 save writes no pending record")
        assertFalse(r.redeemsCode)
        val noId = SetupPlan.evaluate(Fixtures.ENROLLMENT_TODAY, "") as SetupResult.Ok
        assertTrue(SettingsKeys.ENROLL_ID in noId.removals())
    }

    @Test
    fun `format-2 writes leave the phone's login alone - no ha_token, a pending record instead`() {
        val r = SetupPlan.evaluate(qr, "") as SetupResult.Ok
        assertTrue(r.redeemsCode)
        assertEquals(
            mapOf(SettingsKeys.HA_URL to "https://ha.example.net", SettingsKeys.PHONE_NAME to "Pixel", SettingsKeys.ENROLL_ID to ENROLL_ID),
            r.writes(),
        )
        assertFalse(r.writes().containsKey(SettingsKeys.HA_TOKEN))
        assertFalse(r.writes().values.any { code in it }, "the code is never in the plain writes")
        assertEquals(emptySet<String>(), r.removals(), "nothing the working login depends on is removed before the redemption")
        val nonce = "n".repeat(48)
        val pw = r.pendingWrites(nonce, 1_700_000_000_000L)
        assertEquals(
            mapOf(
                SettingsKeys.PENDING_CODE to code,
                SettingsKeys.PENDING_NONCE to nonce,
                SettingsKeys.PENDING_ENROLL_ID to ENROLL_ID,
                SettingsKeys.PENDING_HA_URL to "https://ha.example.net",
                SettingsKeys.PENDING_SINCE to "1700000000000",
            ),
            pw,
        )
        assertEquals(PendingCodec.keys, pw.keys)
        assertFalse(r.tailnetWrites(1L).values.any { code in it })
    }

    @Test
    fun `a format-2 QR with a tailnet key still joins the tailnet`() {
        val r = SetupPlan.evaluate("""{"portico":2,"ha_url":"https://h.example","code":"$code","phone_name":"P","enroll_id":"$ENROLL_ID","tailscale_authkey":"$TS_KEY"}""", "") as SetupResult.Ok
        assertTrue(r.joinsTailnet)
        assertEquals(TS_KEY, r.tailnetWrites(5L)[SettingsKeys.TAILNET_KEY])
    }

    @Test
    fun `the pending record round-trips and a half-written one is no record`() {
        val r = SetupPlan.evaluate(qr, "") as SetupResult.Ok
        val pw = r.pendingWrites("n".repeat(48), 123L)
        val p = PendingCodec.read { pw[it] }!!
        assertEquals(code, p.code)
        assertEquals("n".repeat(48), p.nonce)
        assertEquals(ENROLL_ID, p.enrollId)
        assertEquals("https://ha.example.net", p.haUrl)
        assertEquals(123L, p.sinceMs)
        for (missing in PendingCodec.keys) assertNull(PendingCodec.read { k -> if (k == missing) null else pw[k] }, missing)
        assertNull(PendingCodec.read { k -> if (k == SettingsKeys.PENDING_SINCE) "yesterday" else pw[k] })
        assertNull(PendingCodec.read { k -> if (k == SettingsKeys.PENDING_NONCE) "short" else pw[k] })
        assertNull(PendingCodec.read { k -> if (k == SettingsKeys.PENDING_CODE) "bad code!" else pw[k] })
        assertNull(PendingCodec.read { null })
    }

    @Test
    fun `completion replaces the old long-lived token and drops the pending record in one commit`() {
        assertEquals(mapOf(SettingsKeys.REFRESH_TOKEN to "R", SettingsKeys.CLIENT_ID to "C"), PendingCodec.completionWrites("R", "C"))
        assertEquals(PendingCodec.keys + SettingsKeys.HA_TOKEN + SettingsKeys.SIGNED_OUT, PendingCodec.completionRemovals)
        assertTrue(PendingCodec.completionRemovals.intersect(PendingCodec.completionWrites("R", "C").keys).isEmpty())
        assertEquals(setOf(SettingsKeys.REFRESH_TOKEN, SettingsKeys.CLIENT_ID), PendingCodec.signOutRemovals)
        assertEquals(mapOf(SettingsKeys.SIGNED_OUT to "1"), PendingCodec.signOutWrites)
    }

    @Test
    fun `printing a payload or a plan shows no code, no token`() {
        val p = ok(qr)
        assertFalse(p.toString().contains(code))
        assertTrue(p.toString().contains("code=present"))
        assertTrue(p.toString().contains("token=absent"))
        val legacy = ok(Fixtures.ENROLLMENT_TODAY)
        assertFalse(legacy.toString().contains(TOKEN))
        assertTrue(legacy.toString().contains("token=present"))
        assertTrue(legacy.toString().contains("code=absent"))
        assertTrue(EnrollmentRefusal.entries.none { code in it.message || TOKEN in it.message })
    }

    @Test
    fun `settings text - legacy lines are byte for byte what they were`() {
        val legacy = mapOf(SettingsKeys.HA_URL to "https://ha.example.net", SettingsKeys.HA_TOKEN to TOKEN, SettingsKeys.PHONE_NAME to "Pixel")
        val snap = SettingsSnapshot.from({ legacy[it] }, true, "inst")
        assertEquals(
            listOf(
                "Home Assistant: ha.example.net",
                "Tailnet dial target: none: dials the address's host name",
                "Access token: present",
                "Phone name: Pixel",
                "Push ring notification (Firebase): not configured",
                "Ring for door calls on this phone: on",
                "Installation id: set",
            ),
            snap.summaryLines(),
        )
        assertTrue(snap.enrolled)
        assertFalse(snap.hasRefreshToken || snap.pendingRedemption || snap.signedOut)
    }

    @Test
    fun `settings text - a refresh-token phone, a waiting code, a signed-out phone`() {
        val base = mapOf(SettingsKeys.HA_URL to "https://ha.example.net", SettingsKeys.PHONE_NAME to "Pixel")
        val refresh = base + mapOf(SettingsKeys.REFRESH_TOKEN to "R-SECRET", SettingsKeys.CLIENT_ID to "C")
        val s1 = SettingsSnapshot.from({ refresh[it] }, true, "inst")
        assertTrue(s1.enrolled)
        assertTrue(s1.summaryLines().contains("Access token: refresh token (renews itself)"))
        assertFalse(s1.summaryLines().joinToString().contains("R-SECRET"))
        // a refresh token without its client id is not a login
        assertFalse(SettingsSnapshot.from({ (base + (SettingsKeys.REFRESH_TOKEN to "R"))[it] }, true, "inst").enrolled)

        val waiting = base + SetupPlan.evaluate(qr, "").let { (it as SetupResult.Ok).pendingWrites("n".repeat(48), 1L) }
        val s2 = SettingsSnapshot.from({ waiting[it] }, true, "inst")
        assertFalse(s2.enrolled, "a scanned code is not yet a login")
        assertTrue(s2.pendingRedemption)
        assertTrue(s2.summaryLines().last().startsWith("Enrolment: finishing"))
        assertFalse(s2.summaryLines().joinToString().contains(code))

        val out = base + (SettingsKeys.SIGNED_OUT to "1")
        val s3 = SettingsSnapshot.from({ out[it] }, true, "inst")
        assertTrue(s3.signedOut)
        assertEquals("Sign-in: signed out of Home Assistant. Enroll this phone again.", s3.summaryLines().last())
        // the marker is ignored once the phone has a login again
        val again = out + (SettingsKeys.HA_TOKEN to TOKEN)
        assertFalse(SettingsSnapshot.from({ again[it] }, true, "inst").signedOut)
    }
}
