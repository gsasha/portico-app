package com.meda.portico.core.enroll

import com.meda.portico.core.auth.PendingCodec
import com.meda.portico.core.enroll.Fixtures.API_KEY
import com.meda.portico.core.enroll.Fixtures.PROJECT_ID
import com.meda.portico.core.enroll.Fixtures.TOKEN
import com.meda.portico.core.enroll.Fixtures.TS_KEY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class SetupPlanTest {

    private fun ok(enroll: String, gs: String): SetupResult.Ok = when (val r = SetupPlan.evaluate(enroll, gs)) {
        is SetupResult.Ok -> r
        is SetupResult.Refused -> fail("expected Ok, got ${r.problems}")
    }

    private val fcmKeys = setOf(SettingsKeys.FCM_PROJECT_ID, SettingsKeys.FCM_SENDER_ID, SettingsKeys.FCM_APP_ID, SettingsKeys.FCM_API_KEY)

    @Test
    fun enrollmentWithoutFcmNeverTouchesTheStoredFirebaseValues() {
        val writes = ok(Fixtures.ENROLLMENT_TODAY, "").writes()
        assertEquals(setOf(SettingsKeys.HA_URL, SettingsKeys.HA_TOKEN, SettingsKeys.PHONE_NAME), writes.keys)
        assertTrue(fcmKeys.none { it in writes })
        assertEquals(TOKEN, writes[SettingsKeys.HA_TOKEN])
    }

    @Test
    fun theTailscaleKeyIsNeverInTheSettingsWrites() {
        // DEV-557: the key is stored, but only through tailnetWrites (with its save time).
        val writes = ok(Fixtures.ENROLLMENT_WITH_FCM, "").writes()
        assertFalse(writes.values.any { TS_KEY in it })
        assertFalse(writes.keys.any { "tailscale" in it })
    }

    @Test
    fun `DEV-557 a key and ha_dial are written with the save time`() {
        val r = ok("""{"ha_url":"https://haos.example.net","ha_token":"$TOKEN","phone_name":"P","tailscale_authkey":"$TS_KEY","ha_dial":"192.168.1.5:443"}""", "")
        assertEquals(
            mapOf(
                SettingsKeys.HA_DIAL to "192.168.1.5:443",
                SettingsKeys.TAILNET_KEY to TS_KEY,
                SettingsKeys.TAILNET_KEY_SAVED_AT to "1700000000000",
            ),
            r.tailnetWrites(1_700_000_000_000L),
        )
        assertEquals(emptySet<String>(), r.tailnetRemovals())
        assertTrue(r.joinsTailnet)
    }

    @Test
    fun `DEV-557 an enrollment without a key or ha_dial drops the previous ones and joins nothing`() {
        val r = ok(Fixtures.ENROLLMENT_MANUAL_TS, "")
        assertEquals(emptyMap<String, String>(), r.tailnetWrites(1L))
        assertEquals(setOf(SettingsKeys.HA_DIAL, SettingsKeys.TAILNET_KEY, SettingsKeys.TAILNET_KEY_SAVED_AT), r.tailnetRemovals())
        assertFalse(r.joinsTailnet)
    }

    @Test
    fun `DEV-557 a Firebase-only save leaves the tailnet part alone`() {
        val withKey = ok(Fixtures.ENROLLMENT_WITH_FCM, "")
        assertTrue(withKey.joinsTailnet)
        val firebaseOnly = SetupResult.Ok(null, withKey.firebase)
        assertEquals(emptyMap<String, String>(), firebaseOnly.tailnetWrites(1L))
        assertEquals(emptySet<String>(), firebaseOnly.tailnetRemovals())
        assertFalse(firebaseOnly.joinsTailnet)
    }

    @Test
    fun fcmBlockFromTheEnrollmentIsWritten() {
        val writes = ok(Fixtures.ENROLLMENT_WITH_FCM, "").writes()
        assertEquals(PROJECT_ID, writes[SettingsKeys.FCM_PROJECT_ID])
        assertEquals(API_KEY, writes[SettingsKeys.FCM_API_KEY])
    }

    @Test
    fun googleServicesAloneUpdatesOnlyFirebase() {
        val r = ok("", Fixtures.GOOGLE_SERVICES_TWO_CLIENTS)
        assertNull(r.enrollment)
        assertEquals(fcmKeys, r.writes().keys)
    }

    @Test
    fun bothInputsAgreeingIsFine() {
        val r = ok(Fixtures.ENROLLMENT_WITH_FCM, Fixtures.GOOGLE_SERVICES_TWO_CLIENTS)
        assertEquals(3 + 4, r.writes().size) // enrollment's three + Firebase's four
    }

    @Test
    fun bothInputsDisagreeingIsRefused() {
        val other = Fixtures.googleServicesOnly("com.meda.portico", apiKey = Fixtures.OTHER_API_KEY)
        val r = SetupPlan.evaluate(Fixtures.ENROLLMENT_WITH_FCM, other)
        assertTrue(r is SetupResult.Refused)
    }

    @Test
    fun nothingPastedIsRefused() {
        assertTrue(SetupPlan.evaluate("  ", "\n") is SetupResult.Refused)
    }

    @Test
    fun plainHttpToAPublicHostStopsTheSave() {
        // PR-J: a scanned or pasted ha_url over plain http to a public host saves nothing.
        val r = SetupPlan.evaluate("""{"ha_url":"http://ha.example.com:8123","ha_token":"$TOKEN","phone_name":"p"}""", "")
        assertTrue(r is SetupResult.Refused, "$r")
        val text = (r as SetupResult.Refused).problems.single()
        assertTrue(text.startsWith("Enrollment: ") && "https" in text, text)
        assertTrue("example.com" !in text && TOKEN !in text, text)
    }

    @Test
    fun plainHttpOnTheLanSaves() {
        val r = ok("""{"ha_url":"http://192.168.1.10:8123","ha_token":"$TOKEN","phone_name":"p"}""", "")
        assertEquals("http://192.168.1.10:8123", r.writes()[SettingsKeys.HA_URL])
        assertEquals(true, r.enrollment!!.plainHttp)
    }

    @Test
    fun anyBadPartRefusesTheWholeSaveAndReportsEachProblem() {
        val r = SetupPlan.evaluate(Fixtures.OLD_APP_TRANSFER_QR, "not json") as SetupResult.Refused
        assertEquals(2, r.problems.size)
        assertTrue(r.problems.none { TOKEN in it })
    }

    // ---- offline registration: enroll_id is stored with the enrollment, and replaced by it -----

    @Test
    fun enrollIdIsWrittenWithTheEnrollment() {
        val r = ok(Fixtures.ENROLLMENT_WITH_ENROLL_ID, "")
        assertEquals(Fixtures.ENROLL_ID, r.writes()[SettingsKeys.ENROLL_ID])
        assertEquals(oldLogin, r.removals()) // an enroll_id is kept; only the previous format-2 login goes (SEC-03)
    }

    private val oldLogin: Set<String> =
        setOf(SettingsKeys.REFRESH_TOKEN, SettingsKeys.CLIENT_ID, SettingsKeys.SIGNED_OUT) + PendingCodec.keys

    @Test
    fun aFormat1EnrollmentRemovesThePreviousFormat2Login() {
        // SEC-03: the login check prefers a stored refresh token, so one left behind would override the new ha_token.
        val r = ok(Fixtures.ENROLLMENT_TODAY, "")
        assertEquals(setOf(SettingsKeys.ENROLL_ID) + oldLogin, r.removals())
    }

    @Test
    fun anEnrollmentWithoutEnrollIdRemovesTheStoredOne() {
        // A new Save replaces it: an old QR must not inherit the previous enrollment's id.
        val r = ok(Fixtures.ENROLLMENT_TODAY, "")
        assertFalse(SettingsKeys.ENROLL_ID in r.writes())
        assertTrue(SettingsKeys.ENROLL_ID in r.removals())
    }

    @Test
    fun aFirebaseOnlySaveLeavesTheEnrollIdAlone() {
        val r = ok("", Fixtures.GOOGLE_SERVICES_TWO_CLIENTS)
        assertFalse(SettingsKeys.ENROLL_ID in r.writes())
        assertEquals(emptySet<String>(), r.removals())
    }
}
