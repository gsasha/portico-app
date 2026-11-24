package com.meda.portico.store

import com.meda.portico.core.content.SizeMemory
import com.meda.portico.core.ha.CameraSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Blocks B6: the phone's camera sizes on their own prefs file - written at once, read back by a new process, fail-safe. */
class PrefsSizeStoreTest {
    private val prefs = FakeSharedPreferences()

    @Test
    fun `a tap is committed (not applied) and a new process reads it back`() {
        val m = SizeMemory(PrefsSizeStore(prefs))
        m.toggle("home", "gate", CameraSize.BIG)
        assertEquals(1, prefs.commits)
        assertEquals(0, prefs.applies)
        val cold = SizeMemory(PrefsSizeStore(prefs))
        assertEquals(CameraSize.SMALL, cold.effective("home", "gate", CameraSize.BIG), "remembered across launches")
    }

    @Test
    fun `nothing saved is no choices, and a value of the wrong type in the file is no choices rather than a crash`() {
        assertNull(PrefsSizeStore(prefs).load())
        assertEquals(0, SizeMemory(PrefsSizeStore(prefs)).count())
        prefs.edit().putInt(PrefsSizeStore.KEY, 7).commit()
        assertEquals(0, SizeMemory(PrefsSizeStore(prefs)).count())
    }

    @Test
    fun `a failed commit keeps the choice for this run`() {
        val failing = FakeSharedPreferences(commitResult = false)
        val m = SizeMemory(PrefsSizeStore(failing))
        assertEquals(CameraSize.SMALL, m.toggle("home", "gate", CameraSize.BIG))
        assertEquals(CameraSize.SMALL, m.effective("home", "gate", CameraSize.BIG))
    }

    @Test
    fun `it has its own file and key - nothing else shares them`() {
        assertEquals("portico_sizes", PrefsSizeStore.FILE)
        for (other in listOf(PrefsPauseStore.FILE, PrefsCallStore.FILE, PrefsStateFile.FILE)) assertNotEquals(other, PrefsSizeStore.FILE)
    }
}
