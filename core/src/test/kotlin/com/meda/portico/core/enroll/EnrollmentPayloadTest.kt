package com.meda.portico.core.enroll

import com.meda.portico.core.enroll.Fixtures.API_KEY
import com.meda.portico.core.enroll.Fixtures.APP_ID
import com.meda.portico.core.enroll.Fixtures.PROJECT_ID
import com.meda.portico.core.enroll.Fixtures.SENDER_ID
import com.meda.portico.core.enroll.Fixtures.TOKEN
import com.meda.portico.core.enroll.Fixtures.TS_KEY
import com.meda.portico.core.net.UrlRefusal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class EnrollmentPayloadTest {

    private fun ok(text: String): EnrollmentPayload = when (val r = EnrollmentPayload.parse(text)) {
        is EnrollmentParseResult.Ok -> r.payload
        is EnrollmentParseResult.Refused -> fail("expected Ok, got ${r.reason}")
    }

    private fun refused(text: String): EnrollmentParseResult.Refused = when (val r = EnrollmentPayload.parse(text)) {
        is EnrollmentParseResult.Ok -> fail("expected Refused, got Ok")
        is EnrollmentParseResult.Refused -> r
    }

    @Test
    fun parsesTodaysServerPayloadWithoutMarkerOrFcm() {
        val p = ok(Fixtures.ENROLLMENT_TODAY)
        assertEquals("https://ha.example.net:8123", p.haUrl)
        assertEquals("ha.example.net", p.haHost)
        assertEquals(TOKEN, p.haToken)
        assertEquals("Bob's Pixel", p.phoneName)
        assertEquals(TS_KEY, p.tailscaleAuthKey)
        assertSame(FcmBlock.Unchanged, p.fcm)
        assertNull(p.formatVersion)
    }

    @Test
    fun manualTailscaleModeHasNoAuthKey() {
        val p = ok(Fixtures.ENROLLMENT_MANUAL_TS)
        assertNull(p.tailscaleAuthKey)
        assertEquals("homeassistant.local", p.haHost)
    }

    @Test
    fun parsesFcmBlockAndMarker() {
        val p = ok(Fixtures.ENROLLMENT_WITH_FCM)
        assertEquals(1, p.formatVersion)
        val fcm = p.fcm as FcmBlock.Present
        assertEquals(PROJECT_ID, fcm.config.projectId)
        assertEquals(SENDER_ID, fcm.config.senderId)
        assertEquals(APP_ID, fcm.config.appId)
        assertEquals(API_KEY, fcm.config.apiKey)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        """{"ha_url":"https://h","ha_token":"t0k","phone_name":"p"}""",
        """{"ha_url":"https://h","ha_token":"t0k","phone_name":"p","fcm":null}""",
        """{"ha_url":"https://h","ha_token":"t0k","phone_name":"p","fcm":{}}""",
        """{"ha_url":"https://h","ha_token":"t0k","phone_name":"p","fcm":""}""",
        """{"ha_url":"https://h","ha_token":"t0k","phone_name":"p","fcm":"   "}""",
    ])
    fun absentOrBlankFcmMeansUnchanged(text: String) {
        assertSame(FcmBlock.Unchanged, ok(text).fcm)
    }

    @Test
    fun acceptsWhitespaceAndUnknownExtraKeys() {
        val p = ok("﻿  \n" + """{"ha_url":"https://h.example","ha_token":"t0k","phone_name":"p","future":{"x":[1,2]}}""" + "\n ")
        assertEquals("h.example", p.haHost)
    }

    @ParameterizedTest
    @ValueSource(strings = ["3", "0", "\"1\"", "1.0", "true", "null", "{}", "\"2\"", "2.0"])
    fun refusesAMarkerThatIsPresentAndNotOneOrTwo(marker: String) {
        val r = refused("""{"portico":$marker,"ha_url":"https://h","ha_token":"t0k","phone_name":"p"}""")
        assertEquals(EnrollmentRefusal.UNSUPPORTED_FORMAT, r.reason)
    }

    @Test
    fun refusesNonJsonKeyValueQrTextsOfARetiredApp() {
        assertEquals(EnrollmentRefusal.NOT_JSON, refused(Fixtures.OLD_APP_TRANSFER_QR).reason)
        assertEquals(EnrollmentRefusal.NOT_JSON, refused(Fixtures.OLD_APP_FCM_QR).reason)
        assertEquals(EnrollmentRefusal.NOT_JSON, refused("  \n" + Fixtures.OLD_APP_TRANSFER_QR).reason)
    }

    @Test
    fun recognisesAGoogleServicesJsonPastedInTheWrongField() {
        assertEquals(EnrollmentRefusal.GOOGLE_SERVICES_JSON, refused(Fixtures.GOOGLE_SERVICES_TWO_CLIENTS).reason)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "hello", "{", "{\"ha_url\":", "{\"a\":1,}", "[1,2]", "\"just a string\"", "42", "null",
        "WIFI:S:home;T:WPA;P:pass;;", "{\"ha_url\":\"https://h\"}}", "{'ha_url':'x'}",
        "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[",
    ])
    fun refusesGarbageWithoutThrowing(text: String) {
        val r = refused(text)
        assertTrue(r.reason in setOf(EnrollmentRefusal.NOT_JSON, EnrollmentRefusal.NOT_AN_OBJECT), "got ${r.reason}")
    }

    @Test
    fun refusesDeepNestingWithoutStackOverflow() {
        // Under the size cap (so the depth cap is what refuses it), far over MiniJson.MAX_DEPTH.
        val deep = "{\"a\":".repeat(2000) + "1" + "}".repeat(2000)
        assertTrue(deep.length < EnrollmentPayload.MAX_CHARS)
        assertEquals(EnrollmentRefusal.NOT_JSON, refused(deep).reason)
    }

    @Test
    fun refusesEmptyAndOversizedInput() {
        assertEquals(EnrollmentRefusal.EMPTY, refused("").reason)
        assertEquals(EnrollmentRefusal.EMPTY, refused(" \n\t ").reason)
        val huge = """{"ha_url":"https://h","ha_token":"${"x".repeat(20_000)}","phone_name":"p"}"""
        assertEquals(EnrollmentRefusal.TOO_LARGE, refused(huge).reason)
    }

    @Test
    fun refusesMissingOrBadFields() {
        assertEquals(EnrollmentRefusal.MISSING_HA_URL, refused("""{"ha_token":"t","phone_name":"p"}""").reason)
        assertEquals(EnrollmentRefusal.HA_URL_NOT_SET, refused("""{"ha_url":"","ha_token":"t","phone_name":"p"}""").reason)
        assertEquals(EnrollmentRefusal.INVALID_HA_URL, refused("""{"ha_url":"ftp://h","ha_token":"t","phone_name":"p"}""").reason)
        assertEquals(EnrollmentRefusal.INVALID_HA_URL, refused("""{"ha_url":"https://","ha_token":"t","phone_name":"p"}""").reason)
        // PR-J: user-info is now refused by UrlPolicy, with its own reason.
        refused("""{"ha_url":"https://u:pw@h","ha_token":"t","phone_name":"p"}""").let {
            assertEquals(EnrollmentRefusal.HA_URL_NOT_ALLOWED, it.reason)
            assertEquals(UrlRefusal.USERINFO, it.url)
        }
        assertEquals(EnrollmentRefusal.INVALID_HA_URL, refused("""{"ha_url":"not a url","ha_token":"t","phone_name":"p"}""").reason)
        assertEquals(EnrollmentRefusal.INVALID_HA_URL, refused("""{"ha_url":42,"ha_token":"t","phone_name":"p"}""").reason)
        assertEquals(EnrollmentRefusal.MISSING_HA_TOKEN, refused("""{"ha_url":"https://h","phone_name":"p"}""").reason)
        assertEquals(EnrollmentRefusal.MISSING_HA_TOKEN, refused("""{"ha_url":"https://h","ha_token":"  ","phone_name":"p"}""").reason)
        assertEquals(EnrollmentRefusal.INVALID_HA_TOKEN, refused("""{"ha_url":"https://h","ha_token":"unknown","phone_name":"p"}""").reason)
        assertEquals(EnrollmentRefusal.INVALID_HA_TOKEN, refused("""{"ha_url":"https://h","ha_token":"a b","phone_name":"p"}""").reason)
        assertEquals(EnrollmentRefusal.INVALID_HA_TOKEN, refused("""{"ha_url":"https://h","ha_token":7,"phone_name":"p"}""").reason)
        assertEquals(EnrollmentRefusal.MISSING_PHONE_NAME, refused("""{"ha_url":"https://h","ha_token":"t"}""").reason)
        assertEquals(EnrollmentRefusal.INVALID_TAILSCALE_KEY,
            refused("""{"ha_url":"https://h","ha_token":"t","phone_name":"p","tailscale_authkey":5}""").reason)
    }

    @Test
    fun blankTailscaleKeyIsTreatedAsAbsent() {
        assertNull(ok("""{"ha_url":"https://h","ha_token":"t","phone_name":"p","tailscale_authkey":""}""").tailscaleAuthKey)
    }

    @Test
    fun refusesABadFcmBlockAsAWhole() {
        val base = """"ha_url":"https://h","ha_token":"t","phone_name":"p""""
        assertEquals(EnrollmentRefusal.FCM_UNSUPPORTED_VERSION,
            refused("""{$base,"fcm":{"v":2,"project_id":"$PROJECT_ID","app_id":"$APP_ID","api_key":"$API_KEY","sender_id":"$SENDER_ID"}}""").reason)
        assertEquals(EnrollmentRefusal.FCM_INVALID,
            refused("""{$base,"fcm":{"v":1,"project_id":"$PROJECT_ID","app_id":"$APP_ID","sender_id":"$SENDER_ID"}}""").reason)
        assertEquals(EnrollmentRefusal.FCM_INVALID, refused("""{$base,"fcm":[1]}""").reason)
        assertEquals(EnrollmentRefusal.FCM_INVALID, refused("""{$base,"fcm":"yes"}""").reason)
        val placeholder = refused("""{$base,"fcm":{"project_id":"unknown","app_id":"$APP_ID","api_key":"$API_KEY","sender_id":"$SENDER_ID"}}""")
        assertEquals(EnrollmentRefusal.FCM_INVALID, placeholder.reason)
        assertEquals(FirebaseField.PROJECT_ID, placeholder.firebase?.field)
    }

    // ---- PR-J: the network URL policy ---------------------------------------------------------

    private fun withUrl(url: String) = """{"ha_url":"$url","ha_token":"$TOKEN","phone_name":"p"}"""

    @Test
    fun httpsIsAcceptedWithoutTheWarning() {
        assertEquals(false, ok(Fixtures.ENROLLMENT_TODAY).plainHttp)
    }

    @ParameterizedTest
    @ValueSource(strings = ["http://homeassistant.local:8123", "http://192.168.1.10:8123", "http://homeassistant:8123", "http://[fd00::10]:8123"])
    fun plainHttpToAPrivateHostIsSavedWithTheWarning(url: String) {
        val p = ok(withUrl(url))
        assertEquals(true, p.plainHttp)
        assertEquals(url, p.haUrl)
    }

    @ParameterizedTest
    @ValueSource(strings = ["http://ha.example.com:8123", "http://10.0.0.1.evil.com", "http://203.0.113.5:8123"])
    fun plainHttpToAPublicHostIsRefusedAndSaysUseHttps(url: String) {
        val r = refused(withUrl(url))
        assertEquals(EnrollmentRefusal.HA_URL_NOT_ALLOWED, r.reason)
        assertEquals(UrlRefusal.HTTP_PUBLIC_HOST, r.url)
        assertTrue("https" in r.message, r.message)
        assertTrue(url !in r.message && "example" !in r.message && "evil" !in r.message, "refusal quotes the URL: ${r.message}")
    }

    @Test
    fun anUnusualAddressFormIsRefusedEvenOverHttps() {
        val r = refused(withUrl("https://127.1:8123"))
        assertEquals(EnrollmentRefusal.HA_URL_NOT_ALLOWED, r.reason)
        assertEquals(UrlRefusal.NON_CANONICAL_IPV4, r.url)
    }

    @Test
    fun everyRefusalHasAHumanMessageThatNeverEchoesInput() {
        for (reason in EnrollmentRefusal.entries) {
            assertTrue(reason.message.isNotBlank(), "$reason has no message")
        }
        val r = refused("""{"portico":9,"ha_url":"https://h","ha_token":"$TOKEN","phone_name":"p"}""")
        assertTrue(TOKEN !in r.message && TOKEN !in r.toString())
    }

    // ---- offline registration: the optional enroll_id ------------------------------------------

    @Test
    fun enrollIdIsReadWhenPresent() {
        assertEquals(Fixtures.ENROLL_ID, ok(Fixtures.ENROLLMENT_WITH_ENROLL_ID).enrollId)
        assertEquals(43, Fixtures.ENROLL_ID.length)
    }

    @Test
    fun enrollIdIsOptionalForOldQrs() {
        assertNull(ok(Fixtures.ENROLLMENT_TODAY).enrollId)
        assertNull(ok(Fixtures.ENROLLMENT_WITH_ENROLL_ID.replace("\"enroll_id\":\"${Fixtures.ENROLL_ID}\"", "\"enroll_id\":null")).enrollId)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"short\"", "\"has space in it 0123456789\"", "\"has.dot.0123456789abcdef\"", "\"\"", "42", "true", "{}"])
    fun aBadEnrollIdRefusesThePayload(bad: String) {
        val text = Fixtures.ENROLLMENT_WITH_ENROLL_ID.replace("\"${Fixtures.ENROLL_ID}\"", bad)
        assertEquals(EnrollmentRefusal.INVALID_ENROLL_ID, refused(text).reason)
    }

    @Test
    fun enrollIdLengthBounds() {
        val sixteen = "A".repeat(16)
        val max = "b".repeat(128)
        assertEquals(sixteen, ok(Fixtures.ENROLLMENT_WITH_ENROLL_ID.replace(Fixtures.ENROLL_ID, sixteen)).enrollId)
        assertEquals(max, ok(Fixtures.ENROLLMENT_WITH_ENROLL_ID.replace(Fixtures.ENROLL_ID, max)).enrollId)
        assertEquals(EnrollmentRefusal.INVALID_ENROLL_ID, refused(Fixtures.ENROLLMENT_WITH_ENROLL_ID.replace(Fixtures.ENROLL_ID, "A".repeat(15))).reason)
        assertEquals(EnrollmentRefusal.INVALID_ENROLL_ID, refused(Fixtures.ENROLLMENT_WITH_ENROLL_ID.replace(Fixtures.ENROLL_ID, "b".repeat(129))).reason)
    }

    @Test
    fun enrollIdNeverInToStringOrRefusal() {
        val p = ok(Fixtures.ENROLLMENT_WITH_ENROLL_ID)
        assertTrue(Fixtures.ENROLL_ID !in p.toString(), p.toString())
        assertTrue("enroll_id=present" in p.toString(), p.toString())
        val r = refused(Fixtures.ENROLLMENT_WITH_ENROLL_ID.replace(Fixtures.ENROLL_ID, Fixtures.ENROLL_ID + " x"))
        assertTrue(Fixtures.ENROLL_ID !in r.message && Fixtures.ENROLL_ID !in r.toString())
    }

    // ---- DEV-557: ha_dial -------------------------------------------------------------------

    private fun withDial(dial: String) =
        """{"ha_url":"https://haos.example.net","ha_token":"t0k","phone_name":"p","ha_dial":$dial}"""

    @Test
    fun `ha_dial absent, null or blank is no target`() {
        assertNull(ok("""{"ha_url":"https://h.example","ha_token":"t","phone_name":"p"}""").haDial)
        assertNull(ok(withDial("null")).haDial)
        assertNull(ok(withDial("\"  \"")).haDial)
    }

    @Test
    fun `ha_dial is kept canonical`() {
        assertEquals("192.168.1.5:443", ok(withDial("\" 192.168.1.5:443 \"")).haDial)
        assertEquals("[fd7a:115c:a1e0::1]:8443", ok(withDial("\"[FD7A:115C:A1E0::1]:8443\"")).haDial)
        assertEquals("100.64.1.2:8123", ok(withDial("\"100.64.1.2:8123\"")).haDial)
    }

    @Test
    fun `an invalid ha_dial refuses the whole payload`() {
        for (bad in listOf("\"haos.example.org:443\"", "\"8.8.8.8:443\"", "\"127.0.0.1:443\"", "\"192.168.1.5\"", "443", "{}", "\"192.168.1.5:99999\"")) {
            assertEquals(EnrollmentRefusal.INVALID_HA_DIAL, refused(withDial(bad)).reason, bad)
        }
    }

    @Test
    fun `toString shows ha_dial but never the tailscale key`() {
        val p = ok("""{"ha_url":"https://h.example","ha_token":"t","phone_name":"p","tailscale_authkey":"tskey-auth-SHOWME-1","ha_dial":"192.168.1.5:443"}""")
        assertTrue("ha_dial=192.168.1.5:443" in p.toString())
        assertFalse("SHOWME" in p.toString())
    }
}
