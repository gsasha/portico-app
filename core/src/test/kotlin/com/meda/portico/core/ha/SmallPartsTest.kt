package com.meda.portico.core.ha

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmallPartsTest {

    @Test
    fun `doors cache maps door id, door ref and endpoint id, persists, and only saves a change`() {
        val store = InMemoryDoorsCacheStore(listOf(Door("d1", "e1", "ep1")))
        val cache = DoorsCache(store)
        assertEquals("e1", cache.doorRefFor("d1"))
        assertEquals("d1", cache.doorIdFor("e1"))
        assertEquals("ep1", cache.endpointIdFor("e1"))
        assertNull(cache.doorRefFor("d2"))
        assertNull(cache.endpointIdFor("nope"))
        assertTrue(cache.knowsDoorId("d1"))
        assertTrue(!cache.knowsDoorId("d2"))
        cache.replaceAll(listOf(Door("d1", "e1", "ep1")))
        assertEquals(0, store.saves)
        cache.replaceAll(listOf(Door("d1", "e1", "ep1"), Door("d2", "e2", "ep2", "dahua_vto")))
        assertEquals(1, store.saves)
        assertEquals(setOf("e1", "e2"), cache.knownDoorRefs())
        assertEquals(setOf("e1", "e2"), DoorsCache(store).knownDoorRefs(), "a new process reads it back")
    }

    @Test
    fun `doors codec round-trips and never throws`() {
        val list = listOf(Door("d1", "e1", "ep1", "x"), Door("d2", "e2"))
        assertEquals(list, DoorCodec.decode(DoorCodec.encode(list)))
        for (bad in listOf(null, "", "{", "{}", "[1]", """[{"door_id":"d"}]""", """[{"door_ref":"r"}]""")) {
            assertEquals(emptyList<Door>(), DoorCodec.decode(bad), "$bad")
        }
    }

    @Test
    fun `a cache written by contract 2's app decodes to nothing - its entry ids are not door refs`() {
        val v2 = """[{"device_id":"gate-sip.example","entry_id":"01J9ENTRYGATE","driver":"gate_sip_observer","name":"Gate"}]"""
        assertEquals(emptyList<Door>(), DoorCodec.decode(v2))
    }

    @Test
    fun `the encoded form names the contract 3 keys and nothing of contract 2`() {
        val text = DoorCodec.encode(listOf(Door("d1", "e1", "ep1", "x", "Gate")))
        assertEquals("""[{"door_id":"d1","door_ref":"e1","endpoint_id":"ep1","driver":"x","name":"Gate"}]""", text)
    }

    @Test
    fun `PR-F - an optional door name is kept by the codec and read by nameFor`() {
        val list = listOf(Door("d1", "e1", driver = "x", name = "Gate"), Door("d2", "e2", name = " "))
        assertEquals(list, DoorCodec.decode(DoorCodec.encode(list)))
        val cache = DoorsCache(InMemoryDoorsCacheStore(list))
        assertEquals("Gate", cache.nameFor("e1"))
        assertEquals(null, cache.nameFor("e2"), "blank is no name")
        assertEquals(null, cache.nameFor("unknown"))
    }

    @Test
    fun `websocket url from ha_url`() {
        assertEquals("ws://ha.local:8123/api/websocket", HaUrls.websocketUrl("http://ha.local:8123"))
        assertEquals("wss://ha.example/api/websocket", HaUrls.websocketUrl("https://ha.example/"))
        assertEquals("wss://ha.example/sub/api/websocket", HaUrls.websocketUrl(" HTTPS://ha.example/sub "))
        assertEquals("ws://h/api/websocket", HaUrls.websocketUrl("ws://h/api/websocket"))
        for (bad in listOf(null, "", "ha.local:8123", "ftp://x", "http://")) assertNull(HaUrls.websocketUrl(bad), "$bad")
    }

    @Test
    fun `backoff doubles, caps, and resets`() {
        val b = Backoff(baseMs = 1_000, maxMs = 10_000, jitter = { it })
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L), List(6) { b.nextDelayMs() })
        repeat(100) { b.nextDelayMs() }
        assertEquals(10_000L, b.nextDelayMs(), "no overflow after many failures")
        b.reset()
        assertEquals(1_000L, b.nextDelayMs())
        val jittered = Backoff(baseMs = 1_000, maxMs = 10_000)
        repeat(50) { val d = jittered.nextDelayMs(); assertTrue(d in 0..10_000) }
    }

    @Test
    fun `a link that was just up is retried every few seconds, an old outage backs off to the long cap`() {
        var now = 1_000_000L
        val b = Backoff(baseMs = 1_000, maxMs = 60_000, jitter = { it }, freshMaxMs = 8_000, freshForMs = 300_000, clockMs = { now })
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L), List(7) { b.nextDelayMs() }, "never connected: the long cap")
        b.markConnected()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L), List(6) { b.nextDelayMs() }, "just dropped: probe every 8 s")
        now += 299_000
        assertEquals(8_000L, b.nextDelayMs())
        now += 2_000 // five minutes since it was last up
        assertEquals(60_000L, b.nextDelayMs(), "the outage is not short any more")
        b.markConnected()
        assertEquals(1_000L, b.nextDelayMs(), "markConnected resets the attempt count")
    }

    @Test
    fun `mask is sha256 short form`() {
        // sha256("abc") = ba7816bf...
        assertEquals("sha:ba7816bf", Mask.short("abc"))
        assertEquals("null", Mask.short(null))
    }
}
