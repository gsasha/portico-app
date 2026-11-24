package com.meda.portico.store

import com.meda.portico.core.ha.Door
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.reg.RegKind
import com.meda.portico.core.reg.RegValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrefsStateStoresTest {
    private val prefs = FakeSharedPreferences()
    private val fid = RegValue(RegKind.FID, "cPYZbX0nQ0mJ9mVdK3x_-A")

    @Test
    fun `doors cache survives a new process (persisted, read at construction)`() {
        val doors = listOf(Door("gate-sip.example", "d-0123456789abcdef0123456789abcdef", "ep:gate", "gate_sip_observer"))
        DoorsCache(PrefsDoorsCacheStore(prefs)).replaceAll(doors)
        val cold = DoorsCache(PrefsDoorsCacheStore(prefs))
        assertEquals(setOf("d-0123456789abcdef0123456789abcdef"), cold.knownDoorRefs())
        assertEquals(doors, cold.all)
        assertEquals(1, prefs.commits)
    }

    @Test
    fun `contract 2's devices cache is discarded on the first read, never half-read`() {
        // what 0.4.3 persisted: device_id and entry_id pairs under devices_v1
        prefs.edit().putString("devices_v1", """[{"device_id":"gate-sip.example","entry_id":"01J9ENTRYGATE","driver":"gate_sip_observer"}]""").commit()
        val cold = DoorsCache(PrefsDoorsCacheStore(prefs))
        assertEquals(emptySet<String>(), cold.knownDoorRefs(), "a push for an old entry id is an unknown door until the first portico/doors")
        assertFalse(prefs.contains(PrefsDoorsCacheStore.OLD_KEY), "the old key is gone")
        assertNull(prefs.getString(PrefsDoorsCacheStore.KEY, null))
        assertEquals("doors_v3", PrefsDoorsCacheStore.KEY)
    }

    @Test
    fun `a new cache is written under its own key and leaves no old key behind`() {
        val store = PrefsDoorsCacheStore(prefs)
        store.save(listOf(Door("d", "d-ref")))
        assertTrue(prefs.contains(PrefsDoorsCacheStore.KEY))
        assertFalse(prefs.contains(PrefsDoorsCacheStore.OLD_KEY))
    }

    @Test
    fun `registration value round trip, scoped to the running Firebase project`() {
        var project: String? = "portico-a"
        val s = PrefsRegistrationStore(prefs) { project }
        s.saveValue(fid)
        assertEquals(fid, PrefsRegistrationStore(prefs) { project }.loadValue())
        project = "portico-b" // a new fcm block applied at restart
        assertNull(s.loadValue(), "an FID from another project is not this project's")
        project = null // Firebase not configured
        assertNull(s.loadValue())
    }

    @Test
    fun `saveValue null clears, and the pending-unregister flag persists`() {
        val s = PrefsRegistrationStore(prefs) { "portico-a" }
        s.saveValue(fid)
        s.saveValue(null)
        assertNull(s.loadValue())
        assertFalse(s.loadPendingUnregister())
        s.savePendingUnregister(true)
        assertTrue(PrefsRegistrationStore(prefs) { "portico-a" }.loadPendingUnregister())
        assertEquals(0, prefs.applies, "commit() only")
    }

    @Test
    fun `toString never renders the value`() {
        val s = PrefsRegistrationStore(prefs) { "portico-a" }
        s.saveValue(fid)
        assertFalse(fid.value in s.toString(), s.toString())
    }
}
