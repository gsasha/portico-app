package com.meda.portico.core.content

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.ContentBlock
import com.meda.portico.core.ha.ContentCamera
import com.meda.portico.core.ha.ContentPage
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Blocks B6 (design 6.2, Q4): the phone's remembered camera sizes. Pure; the store is a variable. */
class SizeMemoryTest {

    private class FakeStore(var text: String? = null, var fail: Boolean = false, var failLoad: Boolean = false) : SizeStore {
        var saves = 0
        override fun load(): String? = if (failLoad) throw IllegalStateException("unreadable") else text
        override fun save(text: String): Boolean {
            saves++
            if (fail) throw IllegalStateException("disk full")
            this.text = text
            return true
        }
    }

    private val big = CameraSize.BIG
    private val small = CameraSize.SMALL

    private fun cam(id: String, size: CameraSize? = null) = ContentCamera(id, id, null, true, size)
    private fun profile(vararg pages: ContentPage) = ContentProfile("", "r", emptyList(), emptyList(), pages = pages.toList())
    private fun page(id: String, vararg cams: ContentCamera) = ContentPage.ofBlocks(id, id, listOf(ContentBlock("b", "B", cams.toList(), emptyList())))

    // ---- the effective size rule ----------------------------------------------------------

    @Test
    fun `with no choice the server's default is the size`() {
        val m = SizeMemory(FakeStore())
        assertEquals(big, m.effective("home", "gate", big))
        assertEquals(small, m.effective("home", "gate", small))
    }

    @Test
    fun `a tap flips to the other size and is remembered while it differs from the default`() {
        val m = SizeMemory(FakeStore())
        assertEquals(small, m.toggle("home", "gate", big))
        assertEquals(small, m.effective("home", "gate", big))
        assertEquals(1, m.count())
    }

    @Test
    fun `a second tap goes back to the default and deletes the choice, nothing is stored for a default`() {
        val store = FakeStore()
        val m = SizeMemory(store)
        m.toggle("home", "gate", big)
        assertEquals(big, m.toggle("home", "gate", big))
        assertEquals(0, m.count())
        assertEquals(0, SizeCodec.decode(store.text).size, "the saved text holds no choice either")
    }

    @Test
    fun `a choice is per page and camera id - another page's camera of the same id is its own`() {
        val m = SizeMemory(FakeStore())
        m.toggle("home", "gate", big)
        assertEquals(small, m.effective("home", "gate", big))
        assertEquals(big, m.effective("garden", "gate", big))
        assertEquals(big, m.effective("home", "garage", big))
    }

    @Test
    fun `Q4 - a choice made against one default is not applied against another`() {
        val m = SizeMemory(FakeStore())
        m.toggle("yard", "gate", small) // chose big against a small default
        assertEquals(big, m.effective("yard", "gate", small))
        // HA made the default big: the choice (made against small) no longer applies; the default is what shows
        assertEquals(big, m.effective("yard", "gate", big))
        // and the other way: chose small against big, HA made it small
        m.toggle("home", "gate", big)
        assertEquals(small, m.effective("home", "gate", small))
        m.toggle("home", "gate", small) // a tap now flips from the shown (small) default: big, a new choice against small
        assertEquals(big, m.effective("home", "gate", small))
        assertEquals(big, m.effective("home", "gate", big))
    }

    @Test
    fun `a stale choice does not come back when the default flips back - it is dropped for good by reconcile, and by the next tap`() {
        val m = SizeMemory(FakeStore())
        m.toggle("home", "gate", big) // choice small, made against big
        // HA changes the camera to small; the next real document drops the choice
        assertTrue(m.reconcile(profile(page("home", cam("gate", small)))))
        assertEquals(0, m.count())
        // HA changes it back to big: the phone shows the default, not the old small
        assertEquals(big, m.effective("home", "gate", big))
    }

    @Test
    fun `a tap after a default change toggles from what is shown, against the new default`() {
        val m = SizeMemory(FakeStore())
        m.toggle("home", "gate", big) // choice small against big
        // the default is now small (HA edited it): the tile shows small, a tap makes it big, which is not the default
        assertEquals(big, m.toggle("home", "gate", small))
        assertEquals(big, m.effective("home", "gate", small))
        assertEquals(big, m.effective("home", "gate", big), "that choice was made against small, so it says nothing about a big default")
        assertEquals(small, m.toggle("home", "gate", big), "and a tap flips the shown size")
    }

    // ---- reconcile -----------------------------------------------------------------------

    @Test
    fun `reconcile keeps a choice whose camera and default are unchanged`() {
        val m = SizeMemory(FakeStore())
        m.toggle("home", "gate", big)
        assertFalse(m.reconcile(profile(page("home", cam("gate", big), cam("yard")))))
        assertEquals(1, m.count())
    }

    @Test
    fun `reconcile uses the auto rule for a camera with no explicit size - the first of a block is big`() {
        val m = SizeMemory(FakeStore())
        m.toggle("home", "gate", big) // gate is first: auto default big
        m.toggle("home", "yard", small) // yard is second: auto default small, choice big
        assertFalse(m.reconcile(profile(page("home", cam("gate"), cam("yard")))))
        assertEquals(2, m.count())
        // the admin moves yard first: its auto default is now big, so its choice (made against small) goes; gate (now small) goes too
        assertTrue(m.reconcile(profile(page("home", cam("yard"), cam("gate")))))
        assertEquals(0, m.count())
    }

    @Test
    fun `reconcile prunes a camera HA removed and a page it removed`() {
        val m = SizeMemory(FakeStore())
        m.toggle("home", "gate", big)
        m.toggle("garden", "pond", big)
        assertTrue(m.reconcile(profile(page("home", cam("gate", big), cam("other")))))
        assertEquals(big, m.effective("garden", "pond", big))
        assertEquals(small, m.effective("home", "gate", big))
        assertEquals(1, m.count(), "garden is gone from the document")
    }

    @Test
    fun `reconcile never forgets from an empty document - a bad fetch cannot wipe the memory`() {
        val store = FakeStore()
        val m = SizeMemory(store)
        m.toggle("home", "gate", big)
        val saves = store.saves
        assertFalse(m.reconcile(ContentProfile.EMPTY))
        assertFalse(m.reconcile(profile(ContentPage.ofBlocks("home", "Home", listOf(ContentBlock("b", "B", emptyList(), emptyList()))))))
        assertEquals(1, m.count())
        assertEquals(saves, store.saves, "nothing was written")
    }

    @Test
    fun `reconcile reads the pages of a document with no blocks too (the wrapped page)`() {
        val m = SizeMemory(FakeStore())
        m.toggle("", "gate", big)
        val flat = ContentProfile("", "r", listOf(cam("gate")), emptyList())
        assertFalse(m.reconcile(flat))
        assertEquals(small, m.effective("", "gate", big))
    }

    // ---- persistence and reset -----------------------------------------------------------

    @Test
    fun `choices survive a new process - a new SizeMemory over the same store`() {
        val store = FakeStore()
        SizeMemory(store).toggle("home", "gate", big)
        val again = SizeMemory(store)
        assertEquals(small, again.effective("home", "gate", big))
        assertEquals(1, again.count())
    }

    @Test
    fun `unreadable or damaged storage is no choices, and a failing write keeps the choice for this run`() {
        assertEquals(0, SizeMemory(FakeStore(failLoad = true)).count())
        for (junk in listOf("", "   ", "{", "[]", "{\"v\":2,\"sizes\":[]}", "{\"v\":1}", "{\"v\":1,\"sizes\":5}", "not json")) assertEquals(0, SizeMemory(FakeStore(junk)).count(), junk)
        val m = SizeMemory(FakeStore(fail = true))
        assertEquals(small, m.toggle("home", "gate", big))
        assertEquals(small, m.effective("home", "gate", big))
    }

    @Test
    fun `the codec skips a half-written entry, an unknown size and a choice equal to its default, and keeps the rest`() {
        val text = """{"v":1,"sizes":[{"p":"a","c":"x","s":"small","d":"big"},{"p":"a","c":"y","s":"huge","d":"big"},{"p":"a","c":"z","s":"big","d":"big"},{"p":"a","s":"big","d":"small"},5]}"""
        val m = SizeCodec.decode(text)
        assertEquals(mapOf(SizeKey("a", "x") to SizeChoice(small, big)), m)
    }

    @Test
    fun `ids with slashes or quotes round trip and do not collide`() {
        val m = SizeMemory(FakeStore())
        val store = FakeStore()
        val a = SizeMemory(store)
        a.toggle("a/b", "c", big)
        a.toggle("a", "b/c", big)
        a.toggle("p\"q", "c\\d", big)
        val b = SizeMemory(store)
        assertEquals(3, b.count())
        assertEquals(small, b.effective("a/b", "c", big))
        assertEquals(small, b.effective("a", "b/c", big))
        assertEquals(small, b.effective("p\"q", "c\\d", big))
        assertEquals(big, m.effective("a/b", "c", big))
    }

    // ---- the model uses it ---------------------------------------------------------------

    private fun view(p: ContentProfile, sizes: SizeMemory?, page: String? = null) =
        HomeModel.build(p, LinkState.CONNECTED, ContentSupport.YES, true, emptySet(), emptySet(), null, page, sizes)

    @Test
    fun `Home's tiles carry the phone's size and the server's default beside it`() {
        val p = profile(page("home", cam("gate"), cam("yard"), cam("pond", big)))
        val m = SizeMemory(FakeStore())
        val before = view(p, m).cameras
        assertEquals(listOf(big, small, big), before.map { it.size })
        assertEquals(before.map { it.size }, before.map { it.defaultSize })
        m.toggle("home", "gate", big)
        m.toggle("home", "yard", small)
        val after = view(p, m).cameras
        assertEquals(listOf(small, big, big), after.map { it.size })
        assertEquals(listOf(big, small, big), after.map { it.defaultSize }, "the default is what a tap compares against")
        assertNotEquals(before, after, "a choice changes the view, so Home lays out again")
    }

    @Test
    fun `no memory means the server's sizes - the ring and in-call paths and old tests are unchanged`() {
        val p = profile(page("home", cam("gate"), cam("yard")))
        assertEquals(view(p, null), view(p, SizeMemory(FakeStore())))
    }

    @Test
    fun `a choice follows its page - the other page of the same camera id keeps the default`() {
        val p = profile(page("home", cam("gate")), page("garden", cam("gate")))
        val m = SizeMemory(FakeStore())
        m.toggle("garden", "gate", big)
        assertEquals(big, view(p, m, "home").cameras.single().size)
        assertEquals(small, view(p, m, "garden").cameras.single().size)
    }

    @Test
    fun `a layout changes at once - three smalls become a big row after the first is toggled`() {
        val p = profile(ContentPage.ofBlocks("home", "H", listOf(ContentBlock("b", "B", listOf(cam("a", small), cam("b", small), cam("c", small)), emptyList()))))
        val m = SizeMemory(FakeStore())
        assertEquals(listOf(listOf(0, 1), listOf(2)), view(p, m).blocks.single().cameraRows.map { it.cells })
        m.toggle("home", "a", small)
        assertEquals(listOf(listOf(0), listOf(1, 2)), view(p, m).blocks.single().cameraRows.map { it.cells })
    }

    // ---- StillSchedule: one camera due now -----------------------------------------------

    @Test
    fun `StillSchedule reset for one camera makes only that camera due, and never bypasses the request guard`() {
        val s = StillSchedule(4_000L)
        val asked = ArrayList<String>()
        s.tick(listOf("a", "b"), 1_000L) { asked.add(it); true }
        asked.clear()
        s.tick(listOf("a", "b"), 2_000L) { asked.add(it); true }
        assertTrue(asked.isEmpty(), "inside the interval nothing is due")
        s.reset("a")
        // a is due, but the guard says a request is already in flight: refused, offered again at the next tick
        s.tick(listOf("a", "b"), 2_250L) { asked.add(it); false }
        assertEquals(listOf("a"), asked)
        asked.clear()
        s.tick(listOf("a", "b"), 2_500L) { asked.add(it); true }
        assertEquals(listOf("a"), asked, "it is offered again until it is accepted, and then waits its interval")
        asked.clear()
        s.tick(listOf("a", "b"), 3_000L) { asked.add(it); true }
        assertTrue(asked.isEmpty())
    }
}
