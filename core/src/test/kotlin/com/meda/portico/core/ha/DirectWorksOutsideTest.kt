package com.meda.portico.core.ha

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-694 P1c: does the direct path work from outside the house? Learned on mobile data, forgotten slowly. */
class DirectWorksOutsideTest {
    private class MemStore : DirectOutsideStore {
        override var forKey: String? = null
        override var works = false
        override var firstFailureAtMs = 0L
    }

    private var now = 1_000_000L
    private var enrol: String? = "https://ha.example.net|"
    private val store = MemStore()
    private val d = DirectWorksOutside(store, { enrol }, { now })
    private val tenMin = DirectWorksOutside.CLEAR_AFTER_MS

    @Test
    fun `unknown until direct opens on mobile data`() {
        assertFalse(d.known())
        d.opened()
        assertTrue(d.known())
    }

    @Test
    fun `not enrolled - nothing is learned`() {
        enrol = null
        d.opened()
        assertFalse(d.known())
    }

    @Test
    fun `one unreachable result does not clear it, two at least ten minutes apart do`() {
        d.opened()
        d.unreachable()
        assertTrue(d.known())
        now += tenMin - 1
        d.unreachable() // the same event seen again within minutes
        assertTrue(d.known())
        now += 2
        d.unreachable()
        assertFalse(d.known())
    }

    @Test
    fun `a direct open in between resets the first failure`() {
        d.opened()
        d.unreachable()
        now += tenMin / 2
        d.opened()
        now += tenMin * 2
        d.unreachable() // a fresh first result, not the second
        assertTrue(d.known())
    }

    @Test
    fun `a different enrollment starts over`() {
        d.opened()
        enrol = "https://other.example.net|192.168.1.5:443"
        assertFalse(d.known())
        d.unreachable()
        assertFalse(d.known())
        d.opened()
        assertTrue(d.known())
    }

    @Test
    fun `a clock that went backwards counts as a first result`() {
        d.opened()
        d.unreachable()
        now -= 10 * tenMin
        d.unreachable()
        assertTrue(d.known())
    }

    @Test
    fun `an unreachable result when nothing is known changes nothing`() {
        d.unreachable()
        assertFalse(d.known())
        assertEquals(0L, store.firstFailureAtMs)
    }

    @Test
    fun `only a hash is stored - never the address`() {
        d.opened()
        assertFalse(store.forKey!!.contains("example"))
        assertEquals(16, store.forKey!!.length)
    }
}
