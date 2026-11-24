package com.meda.portico.core.live

import com.meda.portico.core.live.NalQueue.Item
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread

/** The bounded hand-off to the decoder (DEV-713 S1b): it never grows, and a lagging decoder resyncs on an IDR instead of seeing a torn stream. */
class NalQueueTest {
    private fun nal(n: Int = 10) = ByteArray(n)

    private fun NalQueue.drain(): List<Item> {
        val out = ArrayList<Item>()
        while (true) out += take(0) ?: break
        return out
    }

    private fun List<Item>.keys() = filterIsInstance<Item.Nal>().map { it.key }

    @Test
    fun `units come out in order`() {
        val q = NalQueue()
        q.offerFormat(nal(), nal(), 1280, 720)
        q.offerNal(nal(), 1, true)
        q.offerNal(nal(), 2, false)
        val out = q.drain()
        assertTrue(out[0] is Item.Format)
        assertEquals(listOf(1L, 2L), out.filterIsInstance<Item.Nal>().map { it.ptsUs })
        assertNull(q.take(0))
    }

    @Test
    fun `the item cap - a full queue resyncs, empties itself and refuses non-IDR units until an IDR`() {
        val q = NalQueue(maxItems = 4)
        q.offerFormat(nal(), nal(), 640, 480)
        assertTrue(q.offerNal(nal(), 1, true))
        for (i in 2..3) assertTrue(q.offerNal(nal(), i.toLong(), false))
        assertEquals(4, q.size(), "the parameter sets and 3 units: the cap counts every item")
        assertFalse(q.offerNal(nal(), 4, false), "the next does not fit: resync, and it is dropped")
        assertEquals(1, q.resyncs)
        assertEquals(1, q.size(), "only the parameter sets were kept")
        assertFalse(q.offerNal(nal(), 5, false), "still waiting for an IDR")
        assertFalse(q.offerNal(nal(), 6, false))
        assertTrue(q.offerNal(nal(), 7, true), "an IDR starts the clean run")
        assertTrue(q.offerNal(nal(), 8, false))
        val out = q.drain()
        assertTrue(out.first() is Item.Format, "the newest parameter sets survived the resync")
        assertEquals(listOf(7L, 8L), out.filterIsInstance<Item.Nal>().map { it.ptsUs })
        assertEquals(6L, q.dropped, "the 3 queued units and the 3 refused")
    }

    @Test
    fun `an IDR that finds the queue full is accepted after the resync`() {
        val q = NalQueue(maxItems = 3)
        for (i in 1..3) q.offerNal(nal(), i.toLong(), i == 1)
        assertTrue(q.offerNal(nal(), 4, true))
        assertEquals(listOf(4L), q.drain().filterIsInstance<Item.Nal>().map { it.ptsUs })
        assertEquals(1, q.resyncs)
    }

    @Test
    fun `the byte cap bounds the queue too`() {
        val q = NalQueue(maxItems = 1000, maxBytes = 100, maxNalBytes = 100)
        assertTrue(q.offerNal(nal(60), 1, true))
        assertFalse(q.offerNal(nal(60), 2, false), "60 + 60 > 100")
        assertTrue(q.queuedBytes() <= 100)
        assertEquals(0, q.queuedBytes(), "the resync emptied it")
    }

    @Test
    fun `a unit over the size cap is dropped and forces a resync`() {
        val q = NalQueue(maxNalBytes = 50)
        assertTrue(q.offerNal(nal(40), 1, true))
        assertFalse(q.offerNal(nal(51), 2, false))
        assertFalse(q.offerNal(nal(10), 3, false), "the stream after a lost unit is not trusted until an IDR")
        assertFalse(q.offerNal(nal(51), 4, true), "even an IDR over the cap is refused")
        assertTrue(q.offerNal(nal(50), 5, true))
        assertEquals(listOf(5L), q.drain().filterIsInstance<Item.Nal>().map { it.ptsUs })
    }

    @Test
    fun `a flood never grows past the caps`() {
        val q = NalQueue(maxItems = 20, maxBytes = 1_000)
        var worst = 0
        for (i in 0 until 10_000) {
            q.offerNal(nal(37), i.toLong(), i % 25 == 0)
            worst = maxOf(worst, q.size())
            assertTrue(q.queuedBytes() <= 1_000)
        }
        assertTrue(worst <= 20, "worst $worst")
        assertTrue(q.resyncs > 0)
    }

    @Test
    fun `a consumer waits for an item and close wakes it`() {
        val q = NalQueue()
        var got: Item? = null
        val t = thread { got = q.take(5_000) }
        q.offerNal(nal(), 1, true)
        t.join(5_000)
        assertTrue(got is Item.Nal)
        var woke = false
        val t2 = thread { q.take(5_000); woke = true }
        q.close()
        t2.join(5_000)
        assertTrue(woke)
        assertFalse(q.offerNal(nal(), 2, true), "closed")
        assertEquals(0, q.size())
    }
}
