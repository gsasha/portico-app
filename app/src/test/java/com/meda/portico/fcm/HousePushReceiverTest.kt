package com.meda.portico.fcm

import com.meda.portico.core.call.HousePush
import com.meda.portico.core.ha.Door
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.push.Outcome
import com.meda.portico.core.push.SilentReason
import com.meda.portico.store.FakeSharedPreferences
import com.meda.portico.store.PrefsCallStore
import com.meda.portico.store.PrefsDoorsCacheStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A house ring's push (M7 3b-B): off, it is exactly what it was (ignored, silent); on, it goes to the house coordinator and never to the door decision. */
class HousePushReceiverTest {

    private val t = 1_760_000_000_000L
    private val handler = PushReceiverTest.RecordingHandler()
    private val store = PrefsCallStore(FakeSharedPreferences())
    private val doors = DoorsCache(PrefsDoorsCacheStore(FakeSharedPreferences().also { PrefsDoorsCacheStore(it).save(listOf(Door("gate-sip.example", "01J9ENTRYGATE"))) }))
    private val log = HaLog { _, _ -> }
    private var wakes = 0
    private val dispatcher = OutcomeDispatcher(handler, { wakes++ }, { true }, log)
    private val houseMessages = mutableListOf<Pair<HousePush, Long>>()
    private var houseOn = false
    private val receiver = PushReceiver(store, doors::knownDoorRefs, dispatcher, log, houseOn = { houseOn }, onHouse = { m, n -> houseMessages += m to n })

    private fun house(kind: String = "ring", seq: String = "1") = mapOf(
        "pv" to "2", "kind" to kind, "call_id" to "hso-1-a", "seq" to seq, "t" to "$t", "ct" to "house", "from_ref" to "e-74d2d3ef5c693660b925968962d8d73b",
    ) + (if (kind == "ring") mapOf("exp" to "${t + 45_000}") else emptyMap())

    @Test
    fun `with house calls off a house message is the door path's unknown kind - silent, no wake, nothing for the coordinator`() {
        val o = receiver.onMessage(house(), t + 1_000)
        assertTrue(o is Outcome.Silent && o.reason == SilentReason.IGNORED, "$o")
        assertEquals(listOf("silent"), handler.calls)
        assertEquals(0, wakes)
        assertEquals(emptyList<Pair<HousePush, Long>>(), houseMessages)
    }

    @Test
    fun `with house calls on a house ring goes to the coordinator with the arrival time, and never to the door decision`() {
        houseOn = true
        val o = receiver.onMessage(house(), t + 1_000)
        assertNull(o)
        assertEquals(1, houseMessages.size)
        assertTrue(houseMessages.single().first is HousePush.Ring)
        assertEquals(t + 1_000, houseMessages.single().second)
        assertEquals(emptyList<String>(), handler.calls, "no door handler call: a house ring cannot ring as a door")
        assertEquals(0, wakes)
        assertEquals(null, store.get(com.meda.portico.core.push.CallKey("01J9ENTRYGATE", "hso-1-a")))
    }

    @Test
    fun `a malformed house message, even with house calls on, takes the door path and is ignored there as before`() {
        houseOn = true
        val o = receiver.onMessage(house() - "from_ref", t + 1_000)
        assertTrue(o is Outcome.Silent)
        assertEquals(emptyList<Pair<HousePush, Long>>(), houseMessages)
    }

    @Test
    fun `a door message is unaffected by house calls being on`() {
        houseOn = true
        val door = mapOf("pv" to "2", "kind" to "ring", "door_ref" to "01J9ENTRYGATE", "call_id" to "c1", "seq" to "1", "t" to "$t", "exp" to "${t + 45_000}")
        assertTrue(receiver.onMessage(door, t + 1_000) is Outcome.Ring)
        assertEquals(emptyList<Pair<HousePush, Long>>(), houseMessages)
    }

    @Test
    fun `a house coordinator that throws never takes the service down`() {
        val r = PushReceiver(store, doors::knownDoorRefs, dispatcher, log, houseOn = { true }, onHouse = { _, _ -> error("boom") })
        assertNull(r.onMessage(house(), t))
    }
}
