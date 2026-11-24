package com.meda.portico.core.roaming

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoamingGateTest {
    private class Home(var iso: String = "", var fail: Boolean = false) : HomeCountryStore {
        override fun get(): String = if (fail) throw IllegalStateException("prefs") else iso
        override fun set(iso: String) {
            if (fail) throw IllegalStateException("prefs")
            this.iso = iso
        }
    }

    private val abroadFlag = RoamingFacts(false, true, false, true, notRoamingCap = false, telephonyRoaming = false, hereIso = "gr")
    private val atHomeWifi = RoamingFacts(true, true, true, false, true, false, hereIso = "il")
    private val gap = RoamingFacts(false, false, false, false, true, false)

    @Test
    fun `default is blocked once away, a tap allows the trip, home resets it`() {
        val g = RoamingGate(Home())
        assertFalse(g.blocking())
        g.update(abroadFlag)
        assertTrue(g.blocking())
        g.allowTrip()
        assertFalse(g.blocking())
        assertTrue(g.tripAllowed())
        // a network change abroad (no data for a moment, then mobile again) keeps the consent
        g.update(gap)
        g.update(abroadFlag)
        assertFalse(g.blocking())
        // foreign Wi-Fi ends the question but is not "home": the trip is over, so the next mobile stretch is blocked again
        g.update(RoamingFacts(false, true, true, false, true, false, hereIso = "gr"))
        assertFalse(g.blocking())
        assertFalse(g.tripAllowed())
        g.update(abroadFlag)
        assertTrue(g.blocking())
    }

    @Test
    fun `dont know holds the previous verdict in both directions`() {
        val g = RoamingGate(Home())
        g.update(abroadFlag)
        g.update(gap)
        assertTrue(g.blocking())
        val h = RoamingGate(Home())
        h.update(atHomeWifi)
        h.update(gap)
        assertFalse(h.blocking())
    }

    @Test
    fun `home country is learned only at home and starts empty`() {
        val store = Home()
        val g = RoamingGate(store)
        g.update(abroadFlag.copy(hereIso = "gr"))
        assertEquals("", store.iso) // never learned abroad
        g.update(atHomeWifi)
        assertEquals("il", store.iso)
        // the local-eSIM case now works
        g.update(RoamingFacts(false, true, false, true, true, false, hereIso = "gr"))
        assertTrue(g.blocking())
        g.update(RoamingFacts(false, true, false, true, true, false, hereIso = "il"))
        assertFalse(g.blocking())
    }

    @Test
    fun `a failing store fails open`() {
        val g = RoamingGate(Home(fail = true))
        g.update(atHomeWifi)
        g.update(RoamingFacts(false, true, false, true, true, false, hereIso = "gr"))
        assertFalse(g.blocking()) // no country to compare, no flag
    }

    @Test
    fun `allowTrip while not away does nothing`() {
        val g = RoamingGate(Home())
        g.allowTrip()
        assertFalse(g.tripAllowed())
        g.update(abroadFlag)
        assertTrue(g.blocking())
    }
}
