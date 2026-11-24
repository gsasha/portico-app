package com.meda.portico.core.enroll

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The "This phone" block names where the tailnet path dials. */
class DialTargetSummaryTest {
    private fun line(dial: String?): String {
        val stored = mapOf(SettingsKeys.HA_URL to "https://haos.example.net/", SettingsKeys.HA_DIAL to dial)
        return SettingsSnapshot.from({ stored[it] }, ringOnThisPhone = true, installationId = null)
            .summaryLines().single { it.startsWith("Tailnet dial target:") }
    }

    @Test
    fun `the QR's ha_dial`() {
        assertEquals("Tailnet dial target: 192.168.1.5:443", line("192.168.1.5:443"))
    }

    @Test
    fun `absent - dials the address's host name`() {
        assertEquals("Tailnet dial target: none: dials the address's host name", line(null))
    }

    @Test
    fun `a stored value that no longer validates is shown as absent, never raw`() {
        assertEquals("Tailnet dial target: none: dials the address's host name", line("user:pw@8.8.8.8:443"))
    }

    @Test
    fun `it sits right under the Home Assistant line`() {
        val lines = SettingsSnapshot.from({ null }, ringOnThisPhone = true, installationId = null).summaryLines()
        assertTrue(lines[0].startsWith("Home Assistant:"))
        assertTrue(lines[1].startsWith("Tailnet dial target:"))
    }
}
