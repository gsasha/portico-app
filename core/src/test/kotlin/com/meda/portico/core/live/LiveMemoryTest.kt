package com.meda.portico.core.live

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.ContentBlock
import com.meda.portico.core.ha.ContentCamera
import com.meda.portico.core.ha.ContentPage
import com.meda.portico.core.ha.ContentProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** the owner's decision 1: the chip's choice is remembered only while it differs from the document's `live` (the card's rule). */
class LiveMemoryTest {
    private class Store(var text: String? = null, var fail: Boolean = false) : LiveStore {
        var saves = 0
        override fun load(): String? = if (fail) throw IllegalStateException("x") else text
        override fun save(text: String): Boolean {
            saves++
            this.text = text
            return true
        }
    }

    private fun profile(vararg cams: Pair<String, Boolean>) = ContentProfile(
        "", "r1", emptyList(), emptyList(),
        pages = listOf(ContentPage.ofBlocks("p", "P", listOf(ContentBlock("b", null, cams.map { (id, live) -> ContentCamera(id, id, null, true, CameraSize.BIG, live) }, emptyList())))),
    )

    @Test
    fun `with no choice the document decides`() {
        val m = LiveMemory(Store())
        assertTrue(m.effective("gate", true))
        assertFalse(m.effective("gate", false))
    }

    @Test
    fun `a tap against the document is remembered, a tap back forgets it`() {
        val s = Store()
        val m = LiveMemory(s)
        assertTrue(m.toggle("gate", false), "document still: tap makes it live")
        assertEquals(1, m.count())
        assertTrue(m.effective("gate", false))
        assertFalse(m.toggle("gate", false), "tap again: back to the document's value")
        assertEquals(0, m.count(), "a choice equal to the document is no choice: nothing is stored")
        assertEquals(2, s.saves)
        assertFalse(m.toggle("lawn", true), "document live: tap makes it a still")
        assertFalse(m.effective("lawn", true))
    }

    @Test
    fun `the choice survives a restart`() {
        val s = Store()
        LiveMemory(s).toggle("gate", false)
        LiveMemory(s).toggle("lawn", true)
        val again = LiveMemory(s)
        assertTrue(again.effective("gate", false), "chose live against a still document")
        assertFalse(again.effective("lawn", true), "chose the still against a live document")
        assertEquals(2, again.count())
    }

    @Test
    fun `reconcile forgets a camera that left or whose document value changed, never from an empty document`() {
        val s = Store()
        val m = LiveMemory(s)
        m.toggle("gate", false)
        m.toggle("lawn", false)
        assertFalse(m.reconcile(ContentProfile.EMPTY), "an empty fetch wipes nothing")
        assertEquals(2, m.count())
        assertFalse(m.reconcile(profile("gate" to false, "lawn" to false)))
        assertTrue(m.reconcile(profile("gate" to true)), "lawn left the document and gate's live changed")
        assertEquals(0, m.count())
    }

    @Test
    fun `damaged storage is no choices, and a store that throws still works for this run`() {
        assertEquals(0, LiveMemory(Store("not json")).count())
        assertEquals(0, LiveMemory(Store("""{"v":2,"live":[{"c":"g","l":true,"d":false}]}""")).count(), "a foreign version")
        assertEquals(0, LiveMemory(Store("""{"v":1,"live":[{"c":"g","l":true,"d":true},{"c":"h"},"x"]}""")).count(), "equal to its document, and malformed rows")
        val m = LiveMemory(Store(fail = true))
        assertTrue(m.toggle("gate", false))
        assertTrue(m.effective("gate", false))
        assertNull(LiveCodec.decode(null).entries.firstOrNull())
    }
}
