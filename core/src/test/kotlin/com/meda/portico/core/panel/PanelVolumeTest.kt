package com.meda.portico.core.panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-807 section 10: the app sets the ring and call levels and restores them, also after two rings and after a kill. */
class PanelVolumeTest {
    private class Levels(var alarm: Int = 3, var voice: Int = 2, val max: Int = 10, var refuse: Boolean = false) : StreamLevels {
        val writes = mutableListOf<Pair<PanelStream, Int>>()
        override fun level(stream: PanelStream) = if (stream == PanelStream.ALARM) alarm else voice
        override fun max(stream: PanelStream) = max
        override fun set(stream: PanelStream, level: Int): Boolean {
            writes += stream to level
            if (refuse) return false
            if (stream == PanelStream.ALARM) alarm = level else voice = level
            return true
        }
    }

    private class Disk(var map: Map<PanelStream, Int> = emptyMap()) : SavedLevels {
        override fun load() = map
        override fun save(levels: Map<PanelStream, Int>) { map = levels }
    }

    @Test
    fun `raise sets the configured share of the maximum and restore puts the person's level back`() {
        val l = Levels(); val d = Disk(); val k = PanelVolumeKeeper(l, d)
        assertTrue(k.raise(PanelStream.ALARM, 80))
        assertEquals(8, l.alarm)
        assertEquals(mapOf(PanelStream.ALARM to 3), d.map) // the original is on disk before anything else can fail
        k.restore(PanelStream.ALARM)
        assertEquals(3, l.alarm)
        assertEquals(emptyMap<PanelStream, Int>(), d.map)
    }

    @Test
    fun `two overlapping rings keep the FIRST original and the last one to end restores it - the raised level is never saved`() {
        val l = Levels(); val d = Disk(); val k = PanelVolumeKeeper(l, d)
        k.raise(PanelStream.ALARM, 80)
        k.raise(PanelStream.ALARM, 80) // a second ring while the first still holds the stream
        assertEquals(3, d.map[PanelStream.ALARM])
        k.restore(PanelStream.ALARM)
        assertEquals(8, l.alarm) // one holder left
        k.restore(PanelStream.ALARM)
        assertEquals(3, l.alarm)
        k.restore(PanelStream.ALARM) // idempotent: nothing held, nothing written
        assertEquals(3, l.alarm)
        assertEquals(3, l.writes.count { it.first == PanelStream.ALARM }) // raise, raise, restore
    }

    @Test
    fun `a kill while raised - the next process start puts the saved original back`() {
        val l = Levels(alarm = 8); val d = Disk(mapOf(PanelStream.ALARM to 3)) // what the killed process left
        PanelVolumeKeeper(l, d).restoreAll()
        assertEquals(3, l.alarm)
        assertEquals(emptyMap<PanelStream, Int>(), d.map)
    }

    @Test
    fun `a raise after a kill never overwrites the original that is still on disk`() {
        val l = Levels(alarm = 8); val d = Disk(mapOf(PanelStream.ALARM to 3))
        val k = PanelVolumeKeeper(l, d)
        k.raise(PanelStream.ALARM, 80) // restoreAll has not run yet
        assertEquals(3, d.map[PanelStream.ALARM])
    }

    @Test
    fun `ring and call are separate streams`() {
        val l = Levels(); val d = Disk(); val k = PanelVolumeKeeper(l, d)
        k.raise(PanelStream.ALARM, 80)
        k.raise(PanelStream.VOICE_CALL, 70)
        k.restore(PanelStream.ALARM)
        assertEquals(3, l.alarm)
        assertEquals(7, l.voice)
        k.restore(PanelStream.VOICE_CALL)
        assertEquals(2, l.voice)
    }

    @Test
    fun `a refused set reads as not ok - ring_level_ok goes false - and the original is still put back`() {
        val l = Levels(refuse = true); val d = Disk(); val k = PanelVolumeKeeper(l, d)
        assertTrue(k.levelOk(PanelStream.ALARM)) // nothing has failed yet
        assertFalse(k.raise(PanelStream.ALARM, 80))
        assertFalse(k.levelOk(PanelStream.ALARM))
        k.restore(PanelStream.ALARM)
        assertEquals(emptyMap<PanelStream, Int>(), d.map)
        l.refuse = false
        assertTrue(k.raise(PanelStream.ALARM, 80))
        assertTrue(k.levelOk(PanelStream.ALARM))
    }

    @Test
    fun `an unreadable stream is not ok and nothing is saved`() {
        val none = object : StreamLevels {
            override fun level(stream: PanelStream): Int? = null
            override fun max(stream: PanelStream): Int? = null
            override fun set(stream: PanelStream, level: Int) = false
        }
        val d = Disk()
        assertFalse(PanelVolumeKeeper(none, d).raise(PanelStream.ALARM, 80))
        assertEquals(emptyMap<PanelStream, Int>(), d.map)
    }

    @Test
    fun `the target is a rounded share, at least one step and at most the maximum`() {
        assertEquals(8, PanelVolumeKeeper.target(10, 80))
        assertEquals(1, PanelVolumeKeeper.target(10, 1))
        assertEquals(10, PanelVolumeKeeper.target(10, 100))
        assertEquals(10, PanelVolumeKeeper.target(10, 500)) // clamped
        assertEquals(1, PanelVolumeKeeper.target(1, 70))
    }
}
