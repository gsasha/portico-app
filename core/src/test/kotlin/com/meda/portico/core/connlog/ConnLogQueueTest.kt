package com.meda.portico.core.connlog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnLogQueueTest {
    private class Mem : ConnLogStore {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    private val store = Mem()
    private val q = ConnLogQueue(store)
    private fun rep(id: String, pad: Int = 0) = """{"v":1,"id":"$id","pad":"${"x".repeat(pad)}"}"""

    @Test
    fun `reports come out oldest first and are removed by id`() {
        q.add(rep("a")); q.add(rep("b")); q.add(rep("c"))
        assertEquals(listOf("a", "b", "c"), q.batch().map { it.id })
        q.remove(listOf("a", "c"))
        assertEquals(listOf("b"), q.batch().map { it.id })
        assertEquals(1, q.size())
    }

    @Test
    fun `a duplicate id and a line without an id are not queued`() {
        q.add(rep("a")); q.add(rep("a")); q.add("""{"no":"id"}"""); q.add("not json"); q.add("")
        assertEquals(1, q.size())
    }

    @Test
    fun `a batch is bounded by count and bytes but never empty while reports exist`() {
        repeat(30) { q.add(rep("r$it")) }
        assertEquals(20, q.batch().size)
        assertEquals(3, q.batch(maxReports = 3).size)
        val big = ConnLogQueue(Mem()).also { it.add(rep("big", 3000)) }
        assertEquals(1, big.batch(maxBytes = 100).size)
        val two = ConnLogQueue(Mem()).also { it.add(rep("p", 100)); it.add(rep("q", 100)) }
        assertEquals(1, two.batch(maxBytes = 150).size)
    }

    @Test
    fun `the queue is capped by count and by bytes, the oldest dropped and counted`() {
        repeat(105) { q.add(rep("r$it")) }
        assertEquals(ConnLogQueue.MAX_REPORTS, q.size())
        assertEquals("r5", q.batch().first().id)
        assertEquals(5, q.dropped())
        val bytes = ConnLogQueue(Mem())
        repeat(10) { bytes.add(rep("b$it", 40_000)) }
        assertTrue(bytes.size() < 10 && bytes.dropped() > 0)
        assertTrue(bytes.batch(100, Int.MAX_VALUE).sumOf { it.json.length } <= ConnLogQueue.MAX_BYTES)
    }

    @Test
    fun `it survives a restart`() {
        q.add(rep("a")); q.add(rep("b"))
        val again = ConnLogQueue(store)
        assertEquals(listOf("a", "b"), again.batch().map { it.id })
        again.remove(listOf("a"))
        assertEquals(1, ConnLogQueue(store).size())
    }

    @Test
    fun `garbage and a throwing store cost the queue and never the caller`() {
        store.text = "#dropped=x\n\ngarbage\n{\"v\":1,\"id\":\"ok\"}\n{broken"
        assertEquals(listOf("ok"), q.batch().map { it.id })
        val bad = ConnLogQueue(object : ConnLogStore {
            override fun read(): String? = throw IllegalStateException("prefs")
            override fun write(text: String) = throw IllegalStateException("prefs")
        })
        bad.add(rep("a")); bad.remove(listOf("a")); bad.clear()
        assertTrue(bad.isEmpty())
        assertFalse(bad.dropped() > 0)
    }

    @Test
    fun `clear empties it and resets the count`() {
        repeat(105) { q.add(rep("r$it")) }
        q.clear()
        assertTrue(q.isEmpty()); assertEquals(0, q.dropped())
    }
}
