package com.meda.portico.core.scan

import com.meda.portico.core.enroll.FcmBlock
import com.meda.portico.core.enroll.SetupPlan
import com.meda.portico.core.enroll.SetupResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A scanned QR goes through exactly the paste field's path ([SetupPlan.evaluate]): the same
 * validation messages, the same save plan. Only what a paste cannot produce (a cancelled scan, no
 * camera, a camera error, an empty code, a lost hand-off) gets its own notice.
 */
class ScanIntakeTest {

    private fun scan(text: String, googleServices: String = "") = ScanIntake.evaluate(ScanOutcome.Text(text), googleServices)

    @Test
    fun `a valid enrollment QR gives the same save plan as pasting it`() {
        val v = scan(ScanFixtures.ENROLLMENT_TODAY)
        assertTrue(v is ScanVerdict.Save, "$v")
        val pasted = SetupPlan.evaluate(ScanFixtures.ENROLLMENT_TODAY, "") as SetupResult.Ok
        assertEquals(pasted.writes(), (v as ScanVerdict.Save).plan.writes())
        assertEquals(ScanFixtures.TOKEN, v.plan.writes()["ha_token"])
    }

    @Test
    fun `the fcm block in the QR is applied like a pasted one`() {
        val v = scan(ScanFixtures.realisticPayload()) as ScanVerdict.Save
        assertTrue(v.plan.enrollment!!.fcm is FcmBlock.Present)
        assertEquals(ScanFixtures.API_KEY, v.plan.writes()["fcm_api_key"])
    }

    @Test
    fun `the google-services field is taken into account, as Save does`() {
        val gs = """{"project_info":{"project_number":"999999999999","project_id":"other"},
            "client":[{"client_info":{"mobilesdk_app_id":"1:999999999999:android:ffff","android_client_info":{"package_name":"com.meda.portico"}},
            "api_key":[{"current_key":"AIzaSyFAKERapiKEY00000000000000000000"}]}]}"""
        val v = scan(ScanFixtures.realisticPayload(), gs)
        assertEquals(ScanVerdict.Refused(listOf(DIFFER)), v)
        assertEquals(SetupPlan.evaluate(ScanFixtures.realisticPayload(), gs), SetupResult.Refused(listOf(DIFFER)))
    }

    @Test
    fun `a non-Portico QR is refused with the paste field's message`() {
        for (text in listOf("https://example.com/menu", "WIFI:T:WPA;S:home;P:secret;;", "[1,2,3]", "{\"hello\":1}")) {
            val v = scan(text)
            val pasted = SetupPlan.evaluate(text, "") as SetupResult.Refused
            assertEquals(ScanVerdict.Refused(pasted.problems), v, text)
        }
    }

    @Test
    fun `a retired app's key=value QR is refused as not JSON`() {
        val v = scan(ScanFixtures.OLD_APP_TRANSFER_QR) as ScanVerdict.Refused
        assertTrue(v.problems.single().contains("not valid JSON"), v.problems.single())
        val fcm = scan(ScanFixtures.OLD_APP_FCM_QR) as ScanVerdict.Refused
        assertTrue(fcm.problems.single().contains("not valid JSON"))
    }

    @Test
    fun `a foreign format marker is refused`() {
        val v = scan(ScanFixtures.ENROLLMENT_TODAY.replace("{", "{\"portico\":3,")) as ScanVerdict.Refused
        assertTrue(v.problems.single().contains("format this version of Portico does not read"))
    }

    @Test
    fun `an empty QR is a notice, never a google-services-only save`() {
        val gs = ScanFixtures.GOOGLE_SERVICES
        assertEquals(ScanVerdict.Notice(ScanNotice.EMPTY_CODE), scan("", gs))
        assertEquals(ScanVerdict.Notice(ScanNotice.EMPTY_CODE), scan(" \n\t", gs))
        // The paste path WOULD save the google-services.json alone: a scan must not.
        assertTrue(SetupPlan.evaluate("", gs) is SetupResult.Ok)
    }

    @Test
    fun `cancelled, no camera, camera failure and a lost hand-off are notices`() {
        assertEquals(ScanVerdict.Notice(ScanNotice.CANCELLED), ScanIntake.evaluate(ScanOutcome.Cancelled, ""))
        assertEquals(ScanVerdict.Notice(ScanNotice.NO_CAMERA), ScanIntake.evaluate(ScanOutcome.NoCamera, ""))
        assertEquals(ScanVerdict.Notice(ScanNotice.CAMERA_FAILED), ScanIntake.evaluate(ScanOutcome.CameraFailed, ""))
        assertEquals(ScanVerdict.Notice(ScanNotice.LOST), ScanIntake.evaluate(ScanOutcome.Lost, ""))
    }

    @Test
    fun `the hand-off result maps to an outcome`() {
        ScanHandoff.clear()
        assertEquals(ScanOutcome.Cancelled, ScanIntake.fromActivityResult(resultOk = false))
        assertEquals(ScanOutcome.Lost, ScanIntake.fromActivityResult(resultOk = true))
        ScanHandoff.put(ScanFixtures.ENROLLMENT_TODAY)
        val o = ScanIntake.fromActivityResult(resultOk = true)
        assertTrue(o is ScanOutcome.Text)
        assertEquals(ScanFixtures.ENROLLMENT_TODAY, (o as ScanOutcome.Text).text)
        assertEquals(null, ScanHandoff.take(), "taking the result empties the hand-off")
        // A cancel after a stale put still drops the stale text.
        ScanHandoff.put(ScanFixtures.ENROLLMENT_TODAY)
        assertEquals(ScanOutcome.Cancelled, ScanIntake.fromActivityResult(resultOk = false))
        assertEquals(null, ScanHandoff.take())
    }

    private companion object {
        const val DIFFER = "The enrollment's Firebase block and the pasted google-services.json differ. Paste only one of them."
    }
}
