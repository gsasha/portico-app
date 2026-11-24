package com.meda.portico.core.call

import com.meda.portico.core.push.MiniJson
import com.meda.portico.core.push.PushMessage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Answering a house ring as a session (the placed call's machinery, one more way in), the push tail, and the caller references (M7 3b-B). */
class HouseReceiveTest {

    // ---- answering is a PlaceSession --------------------------------------------------------------------------------

    private val cmds = FakeCommands()
    private val media = FakePlaceMedia()
    private val audio = FakeAudio()
    private val sched = FakeScheduler()
    private val request = PlaceRequest("house-answer", "Bob", answerCallId = "hso-1")
    private val s by lazy { PlaceSession(request, cmds, media, audio, { true }, sched, { 5_000_000L }, config = PlaceConfig(), onChange = {}) }

    private fun granted() = buildJsonObject { put("call_id", "hso-1") }

    @Test
    fun `an answered ring sends house answer with the call id and client kind app - never content run`() {
        s.begin()
        assertEquals(listOf("portico/house/answer"), cmds.types())
        assertEquals(obj("call_id" to "hso-1", "client_kind" to "app"), cmds.sent.single().body)
        assertTrue(s.snapshot.answering)
        assertEquals(PlacePhase.PLACING, s.phase)
    }

    @Test
    fun `the grant goes straight to connecting - there is no ringing to wait for - and the leg opens with the call id`() {
        s.begin()
        cmds.last("portico/house/answer").ok(granted())
        assertEquals(PlacePhase.CONNECTING, s.phase)
        assertEquals(listOf("hso-1"), media.callIds)
        assertEquals(1, audio.enters)
        media.legs.single().listener.onConnected()
        assertEquals(PlacePhase.IN_CALL, s.phase)
    }

    @Test
    fun `hanging up an answered house call is the house hang-up with the call id alone`() {
        s.begin()
        cmds.last("portico/house/answer").ok(granted())
        media.legs.single().listener.onConnected()
        s.hangUp()
        val h = cmds.last("portico/house/hangup")
        assertEquals(obj("call_id" to "hso-1"), h.body)
        assertEquals(0, cmds.count("portico/hangup"))
    }

    @Test
    fun `the caller hanging up ends the answered call without another command`() {
        s.begin()
        cmds.last("portico/house/answer").ok(granted())
        media.legs.single().listener.onConnected()
        s.onHouseCall(HouseCallEvent("hso-1", "ended", "hangup", "ep-bob", "ep-alice"))
        assertEquals(PlacePhase.ENDED, s.phase)
        assertEquals(EndReason.REMOTE_HANGUP, s.snapshot.endReason)
        assertEquals(0, cmds.count("portico/house/hangup"))
    }

    @Test
    fun `a refused answer says so in plain words and sends nothing more`() {
        s.begin()
        cmds.last("portico/house/answer").error("already_answered")
        assertEquals(PlacePhase.ENDED, s.phase)
        assertEquals("Someone else already answered.", s.snapshot.failure!!.message)
        assertEquals(1, cmds.sent.size)
    }

    @Test
    fun `a door ring ends an answered house call first, the door outranks it`() {
        s.begin()
        cmds.last("portico/house/answer").ok(granted())
        media.legs.single().listener.onConnected()
        s.onDoorRinging()
        assertEquals(PlacePhase.ENDED, s.phase)
        assertEquals(PlaceFailure.Kind.DOOR_RINGING, s.snapshot.failure!!.kind)
        assertEquals(1, cmds.count("portico/house/hangup"))
        assertEquals(1, media.legs.single().closes)
    }

    @Test
    fun `an answer that arrives after the press was abandoned is released with the house hang-up`() {
        s.begin()
        s.hangUp() // PLACING: waits for the reply
        cmds.last("portico/house/answer").ok(granted())
        assertEquals(PlacePhase.ENDED, s.phase)
        assertEquals(1, cmds.count("portico/house/hangup"))
        assertEquals(emptyList<String>(), media.callIds, "no audio was ever opened for it")
    }

    @Test
    fun `the call screen says Answering while the answer is sent, not Calling`() {
        s.begin()
        assertEquals(PlaceUi.Status.ANSWERING, PlaceUi.status(s.snapshot))
        val placed = PlaceSnapshot(PlacePhase.PLACING)
        assertEquals(PlaceUi.Status.PLACING, PlaceUi.status(placed))
    }

    @Test
    fun `the coordinator answers a house ring through the same one-call-at-a-time path`() {
        val cmds2 = FakeCommands()
        val live = mutableListOf<PlaceSession>()
        val c = PlaceCoordinator(
            newSession = { req, hooks, onChange -> PlaceSession(req, cmds2, media, audio, { true }, sched, { 1L }, hooks, onChange = onChange).also { live += it } },
            post = { it() }, serviceStart = {}, serviceStop = {}, paused = { false },
        )
        c.answerHouse("hso-1", "Bob")
        assertEquals("portico/house/answer", cmds2.sent.single().type)
        c.answerHouse("hso-2", "Dani") // another call while one is live: refused, never switched
        assertEquals(1, cmds2.sent.size)
        assertEquals(PlaceFailure.Kind.ANOTHER_CALL, c.state().snapshot.failure!!.kind)
    }

    // ---- the push tail ----------------------------------------------------------------------------------------------

    private fun map(vararg p: Pair<String, String>) = mapOf(*p)

    private val ringMap = map("pv" to "2", "kind" to "ring", "call_id" to "hso-1-a", "seq" to "1", "t" to "1759700000000", "ct" to "house", "from_ref" to "e-53ad1c9e009cadb54f47fc9e5cd16831", "exp" to "1759700045000")

    @Test
    fun `a well-formed house ring, answered and ended parse - and carry no door`() {
        assertEquals(HousePush.Ring("hso-1-a", "e-53ad1c9e009cadb54f47fc9e5cd16831", 1, 1_759_700_000_000L, 1_759_700_045_000L), HousePush.parse(ringMap))
        assertTrue(HousePush.parse(ringMap + ("kind" to "answered") + ("seq" to "2") - "exp") is HousePush.Answered)
        assertTrue(HousePush.parse(ringMap + ("kind" to "ended") + ("seq" to "3") - "exp") is HousePush.Ended)
    }

    @Test
    fun `anything else is not a house message - it falls through to the door path and is ignored there as before`() {
        assertNull(HousePush.parse(ringMap - "ct"))
        assertNull(HousePush.parse(ringMap + ("ct" to "door")))
        assertNull(HousePush.parse(ringMap + ("pv" to "1")))
        assertNull(HousePush.parse(ringMap - "from_ref"))
        assertNull(HousePush.parse(ringMap + ("from_ref" to "not an id!")))
        assertNull(HousePush.parse(ringMap - "exp"), "a ring needs its window")
        assertNull(HousePush.parse(ringMap + ("seq" to "0")))
        assertNull(HousePush.parse(ringMap + ("t" to "12x")))
        assertNull(HousePush.parse(ringMap + ("kind" to "test")))
        // and the door decision still treats the same map as it did before 3b: ignored, UNKNOWN_KIND
        val door = PushMessage.parse(ringMap)
        assertTrue(door is PushMessage.Ignored && door.reason == com.meda.portico.core.push.IgnoreReason.UNKNOWN_KIND)
    }

    @Test
    fun `every v2_1 house case of the vendored fixture is a house message the coordinator decides as the design says`() {
        @Suppress("UNCHECKED_CAST")
        val cases = (MiniJson.parse(String(javaClass.getResourceAsStream("/push_v2.json")!!.readBytes(), Charsets.UTF_8)) as Map<String, Any?>)["cases"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val house = cases.filter { c -> (c["name"] as String).startsWith("v2.1 ") && (c["messages"] as List<Map<String, String>>).any { it["ct"] == "house" } }
        assertEquals(6, house.size, "the fixture's six v2.1 house cases")
        val rigs = HashMap<String, HouseRingRig>()
        for (c in house) {
            val name = c["name"] as String
            val r = HouseRingRig()
            rigs[name] = r
            @Suppress("UNCHECKED_CAST")
            for (m in c["messages"] as List<Map<String, String>>) {
                r.now = (c["now_ms"] as Long)
                val hp = HousePush.parse(m)
                if (hp != null) r.c.onPush(hp, r.now)
            }
        }
        fun rig(prefix: String) = rigs.entries.single { it.key.startsWith(prefix) }.value
        // a ring for an away phone: shown, with the roster's name for the reference
        assertEquals("Bob's phone", rig("v2.1 a house ring for an away phone").shown.single().name)
        // arrives late: silent
        assertEquals(0, rig("v2.1 a house ring that arrives late").shown.size)
        // then answered elsewhere / then ended: shown, then withdrawn
        assertEquals(1, rig("v2.1 a house ring then answered elsewhere").shown.size)
        assertNull(rig("v2.1 a house ring then answered elsewhere").c.state().ring)
        assertNull(rig("v2.1 a house ring then ended").c.state().ring)
        // same call id as a door call: the house ring is shown (the door's own ring is the door path's business)
        assertEquals(1, rig("v2.1 a house ring does not block a door ring").shown.size)
        // a house ended never touches a door call: no ring was up here, nothing to withdraw, nothing shown
        assertEquals(0, rig("v2.1 a house ended does not end a door call").shown.size)
    }

    // ---- who is calling --------------------------------------------------------------------------------------------

    @Test
    fun `from_ref is the server's function of the endpoint id`() {
        assertEquals("e-53ad1c9e009cadb54f47fc9e5cd16831", HouseCallers.refFor("ep-bob"), "the fixture's reference is ep-bob's")
        assertEquals("e-3171d89ad00530ffa19a244f040e9401", HouseCallers.refFor("kitchen"))
    }

    @Test
    fun `a reference resolves only to an endpoint the document names, and the name never falls back to the id`() {
        val r = HouseRingRig()
        assertEquals("ep-bob", HouseCallers.endpointForRef(r.profile, HouseCallers.refFor("ep-bob")))
        assertEquals("kitchen", HouseCallers.endpointForRef(r.profile, HouseCallers.refFor("kitchen")))
        assertNull(HouseCallers.endpointForRef(r.profile, HouseCallers.refFor("ep-unknown")))
        assertNull(HouseCallers.endpointForRef(r.profile, HouseCallers.refFor("*")), "the fallback entry is not an endpoint")
        assertEquals("Kitchen screen", HouseCallers.nameFor(r.profile, "kitchen"))
        assertEquals("Bob's phone", HouseCallers.nameFor(r.profile, "ep-bob"))
        assertNull(HouseCallers.nameFor(r.profile, "ep-unknown"))
        assertNull(HouseCallers.nameFor(r.profile, null))
        assertNotNull(HouseCallers.knownEndpoints(r.profile))
        assertFalse("*" in HouseCallers.knownEndpoints(r.profile))
    }
}
