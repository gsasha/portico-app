package com.meda.portico.core.scan

import com.meda.portico.core.enroll.Fixtures
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * No value from a scanned code may appear in any text the scan path produces: the outcome's and
 * the verdict's toString() (what a log line or a crash report would print), the refusal
 * messages, the hand-off's toString(), the decoder's toString(). The scan path has no log calls
 * at all; this pins that what it could print is clean.
 */
class NoSecretsInScanTextTest {

    private val secrets = listOf(
        ScanFixtures.HA_LONG_LIVED_TOKEN,
        ScanFixtures.API_KEY,
        ScanFixtures.TS_KEY,
        Fixtures.TOKEN,
        Fixtures.TS_KEY,
    )

    private fun assertClean(text: String) {
        for (s in secrets) assertFalse(s in text, "secret leaked into: ${text.replace(s, "<LEAK>")}")
        assertFalse("tskey-" in text && "tskey-auth-k" in text, "a Tailscale key leaked")
    }

    @Test
    fun `outcome, verdict and hand-off texts hold no scanned value`() {
        for (raw in listOf(ScanFixtures.realisticPayload(), Fixtures.ENROLLMENT_TODAY)) {
            val outcome = ScanOutcome.Text(raw)
            assertClean(outcome.toString())
            val verdict = ScanIntake.evaluate(outcome, "")
            assertTrue(verdict is ScanVerdict.Save, "$verdict")
            assertClean(verdict.toString())
            ScanHandoff.put(raw)
            assertClean(ScanHandoff.toString())
            assertClean(ScanIntake.fromActivityResult(resultOk = true).toString())
            assertClean(ScanHandoff.toString())
        }
        assertClean(QrFrameDecoder().toString())
    }

    @Test
    fun `refusals of scanned codes quote nothing from them`() {
        val refusedTexts = listOf(
            ScanFixtures.realisticPayload().replace("\"portico\":1", "\"portico\":2"),
            ScanFixtures.realisticPayload().replace("\"v\":1", "\"v\":9"),
            ScanFixtures.realisticPayload().replace("\"https://", "\"ftp://"),
            ScanFixtures.realisticPayload().dropLast(3),
            Fixtures.OLD_APP_TRANSFER_QR,
            Fixtures.OLD_APP_FCM_QR,
        )
        for (raw in refusedTexts) {
            val v = ScanIntake.evaluate(ScanOutcome.Text(raw), "")
            assertTrue(v is ScanVerdict.Refused, "$v")
            assertClean(v.toString())
            assertClean((v as ScanVerdict.Refused).problems.joinToString())
        }
    }
}
