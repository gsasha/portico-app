package com.meda.portico.core.enroll

import com.meda.portico.core.enroll.Fixtures.API_KEY
import com.meda.portico.core.enroll.Fixtures.TOKEN
import com.meda.portico.core.enroll.Fixtures.TS_KEY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The HA token, the Tailscale key and the Firebase api_key must never appear in any text this
 * package produces: toString() (what a log line or a crash report would print), refusal
 * messages, or the Setup summary.
 */
class NoSecretsInTextTest {

    private val secrets = listOf(TOKEN, TS_KEY, API_KEY, Fixtures.ENROLL_ID)

    private fun assertClean(text: String) {
        for (s in secrets) assertFalse(s in text, "secret leaked into: $text".replace(s, "<LEAK>"))
    }

    @Test
    fun payloadAndResultToStringHideSecrets() {
        val r = EnrollmentPayload.parse(Fixtures.ENROLLMENT_WITH_FCM)
        val p = (r as EnrollmentParseResult.Ok).payload
        assertClean(r.toString())
        assertClean(p.toString())
        assertClean(p.fcm.toString())
        assertTrue("ha.example.net" in p.toString())
        assertTrue("token=present" in p.toString())
    }

    @Test
    fun firebaseConfigToStringHidesTheApiKey() {
        val c = (GoogleServicesParser.extract(Fixtures.GOOGLE_SERVICES_TWO_CLIENTS) as GoogleServicesResult.Ok).config
        assertClean(c.toString())
        assertClean(GoogleServicesParser.extract(Fixtures.GOOGLE_SERVICES_TWO_CLIENTS).toString())
        assertClean(GoogleServicesParser.extractValues(Fixtures.GOOGLE_SERVICES_TWO_CLIENTS, GoogleServicesParser.PORTICO_PACKAGE).toString())
    }

    @Test
    fun setupResultsHideSecrets() {
        val ok = SetupPlan.evaluate(Fixtures.ENROLLMENT_WITH_FCM, Fixtures.GOOGLE_SERVICES_TWO_CLIENTS)
        assertClean(ok.toString())
        assertClean(SetupPlan.evaluate(Fixtures.ENROLLMENT_WITH_ENROLL_ID, "").toString())
        val refused = SetupPlan.evaluate(Fixtures.ENROLLMENT_WITH_FCM.replace("\"portico\":1", "\"portico\":2"), "{")
        assertClean(refused.toString())
        assertClean((refused as SetupResult.Refused).problems.joinToString())
    }

    @Test
    fun summaryShowsHostAndTokenPresenceOnly() {
        val stored = mapOf(
            SettingsKeys.HA_URL to "https://ha.example.net:8123/",
            SettingsKeys.HA_TOKEN to TOKEN,
            SettingsKeys.PHONE_NAME to "Pixel",
            SettingsKeys.ENROLL_ID to Fixtures.ENROLL_ID,
            SettingsKeys.FCM_PROJECT_ID to Fixtures.PROJECT_ID,
            SettingsKeys.FCM_SENDER_ID to Fixtures.SENDER_ID,
            SettingsKeys.FCM_APP_ID to Fixtures.APP_ID,
            SettingsKeys.FCM_API_KEY to API_KEY,
        )
        val snap = SettingsSnapshot.from(stored::get, ringOnThisPhone = true, installationId = "0f8e-uuid")
        val lines = snap.summaryLines()
        val text = lines.joinToString("\n")
        assertClean(text)
        assertClean(snap.toString())
        assertFalse("https://" in text)
        assertTrue("ha.example.net" in text, text)
        assertTrue(lines.any { it.startsWith("Access token:") && it.endsWith("present") }, text)
        assertTrue(Fixtures.PROJECT_ID in text)
    }

    @Test
    fun emptySummary() {
        val snap = SettingsSnapshot.from({ null }, ringOnThisPhone = true, installationId = null)
        val text = snap.summaryLines().joinToString("\n")
        assertTrue("not enrolled" in text, text)
        assertTrue(snap.summaryLines().any { it.startsWith("Access token:") && it.endsWith("absent") }, text)
        assertEquals(false, snap.enrolled)
    }

    @Test
    fun ringSwitchDefaultsOn() {
        assertTrue(SettingsKeys.RING_ON_THIS_PHONE_DEFAULT)
    }
}
