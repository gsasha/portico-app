package com.meda.portico.core.ring

import com.meda.portico.core.enroll.SettingsKeys
import com.meda.portico.core.enroll.SettingsSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class StatusFactsTest {
    private fun snap(vararg kv: Pair<String, String>): SettingsSnapshot {
        val m = kv.toMap()
        return SettingsSnapshot.from({ m[it] }, ringOnThisPhone = false, installationId = "abc")
    }

    private fun keys(f: List<StatusFact>) = f.map { it.key }

    @Test
    fun `while settings are still read only the version shows`() {
        assertEquals(listOf(StatusFact(FactKey.VERSION, "0.6.70-alpha (code 6700)")), StatusFacts.of(null, "0.6.70-alpha (code 6700)"))
    }

    @Test
    fun `an enrolled phone lists address, sign-in, name and version, never the ring switch or the installation id`() {
        val f = StatusFacts.of(
            snap(SettingsKeys.HA_URL to "https://ha.example:8123/x", SettingsKeys.HA_TOKEN to "secret-token", SettingsKeys.PHONE_NAME to "Alice's phone"),
            "v",
        )
        assertEquals(listOf(FactKey.ADDRESS, FactKey.SIGN_IN, FactKey.PHONE_NAME, FactKey.VERSION), keys(f))
        assertEquals("ha.example", f.first { it.key == FactKey.ADDRESS }.value) // host only, never the path
        assertEquals(SignIn.TOKEN.name, f.first { it.key == FactKey.SIGN_IN }.value)
        assertFalse(f.any { it.value.contains("secret-token") })
    }

    @Test
    fun `a not enrolled phone has only the version`() {
        assertEquals(listOf(FactKey.VERSION), keys(StatusFacts.of(snap(), "v")))
    }

    @Test
    fun `a blank phone name is not a fact`() {
        val f = StatusFacts.of(snap(SettingsKeys.HA_URL to "https://ha.example", SettingsKeys.HA_TOKEN to "t", SettingsKeys.PHONE_NAME to "  "), "v")
        assertFalse(FactKey.PHONE_NAME in keys(f))
    }
}
