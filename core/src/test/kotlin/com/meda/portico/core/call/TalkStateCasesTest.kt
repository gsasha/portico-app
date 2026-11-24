package com.meda.portico.core.call

import com.meda.portico.core.ha.Door
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.InMemoryDoorsCacheStore
import com.meda.portico.core.ha.SubscribeTracker
import com.meda.portico.core.ha.WsContract
import com.meda.portico.core.ha.parseTalkState
import com.meda.portico.core.push.InMemoryCallStore
import com.meda.portico.core.push.Outcome
import com.meda.portico.core.push.PushMessage
import com.meda.portico.core.push.Decision
import com.meda.portico.core.push.SocketCallSink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * `talk_state_cases.json`: the APP-SIDE EXPECTATIONS for P3-C, hand-made. The server's recorded `talk_state` frames are
 * vendored in `ws_v3.json` and run by [TalkStateServerFramesTest]; this file keeps only what that recording lacks (the
 * file's own `about` says what and why). Each state case brings a [TalkSession] to a state,
 * delivers the raw events through the same parser the link uses, and checks where the session ended up. Each ring case
 * delivers a raw event through the tracker and the socket sink, or a push data map through the decision, and checks that
 * a dialled call never rings.
 */
class TalkStateCasesTest {

    private val root: JsonObject = Json.parseToJsonElement(
        String(WsContract.resourceBytes("/talk_state_cases.json"), Charsets.UTF_8),
    ).jsonObject

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull

    private val callId = root.str("call_id")!!

    @Test
    fun `the file says it is hand-made and keeps only what the server recording lacks, and the ids are out- ids`() {
        val about = root.str("about")!!
        assertTrue(about.startsWith("APP-SIDE EXPECTATIONS, HAND-MADE, NOT RECORDED FROM THE SERVER"))
        assertTrue("vendored in ws_v3.json" in about && "the server never sends time_cap" in about)
        assertTrue(callId.matches(Regex("out-[0-9a-f]{16}")))
        assertTrue(root.str("other_call_id")!!.matches(Regex("out-[0-9a-f]{16}")))
        assertTrue(root["state_cases"]!!.jsonArray.size >= 14)
        assertTrue(root["ring_cases"]!!.jsonArray.size >= 8)
    }

    @TestFactory
    fun `state cases`(): List<DynamicTest> = (root["state_cases"] as JsonArray).map { c ->
        val case = c.jsonObject
        DynamicTest.dynamicTest(case.str("name")!!) { runStateCase(case) }
    }

    private fun runStateCase(case: JsonObject) {
        val r = TalkRig()
        when (case.str("session")) {
            "none" -> Unit
            "starting" -> r.s.begin()
            "connecting" -> {
                r.s.begin()
                r.grant(callId)
            }
            "in_call" -> {
                r.s.begin()
                r.grant(callId)
                r.s.onTalkState(parseTalkState(talk("in_call"))!!)
                r.media.legs.single().listener.onConnected()
                assertEquals(TalkPhase.IN_CALL, r.phase)
            }
            else -> error("unknown session in the fixture")
        }
        val before = r.cmds.sent.size
        for (e in case["events"]!!.jsonArray) {
            parseTalkState(e.jsonObject)?.let { r.s.onTalkState(it) }
        }
        if (case.str("session") == "starting") r.grant(callId) // the run reply arrives after the events
        val expect = case["expect"]!!.jsonObject
        if (expect["untouched"]?.jsonPrimitive?.booleanOrNull == true) {
            r.assertUntouched()
            return
        }
        assertEquals(expect.str("phase"), r.phase.name)
        expect["station_answered"]?.let { assertEquals(it.jsonPrimitive.boolean, r.s.snapshot.stationAnswered) }
        expect["media_opens"]?.let { assertEquals(it.jsonPrimitive.int, r.media.opens) }
        expect.str("media_call_id")?.let { assertEquals(it, r.media.legs.single().callId) }
        assertEquals(expect.str("end_reason"), r.s.snapshot.endReason?.name)
        assertEquals(expect.str("failure_kind"), r.s.snapshot.failure?.kind?.name)
        val hangups = r.cmds.sent.drop(if (case.str("session") == "starting") 1 else before).count { it.type == "portico/hangup" }
        assertEquals(expect["hangups"]!!.jsonPrimitive.int, hangups)
        if (r.phase == TalkPhase.ENDED) {
            assertEquals(0, r.sched.pending, "no timer left armed")
            assertEquals(r.audio.enters, r.audio.leaves, "audio route left as often as entered")
        }
    }

    private fun talk(state: String): JsonObject = Json.parseToJsonElement(
        """{"type":"talk_state","door_id":"${root.str("door_id")}","call_id":"$callId","state":"$state"}""",
    ).jsonObject

    // ---- the ring cases ----------------------------------------------------------------------------------

    private val ringDoorId = "gate.example"
    private val ringDoorRef = "d-6e5163e1070819ff1e4336aaf9d93c38"
    private val now = 1_760_000_002_000L

    @TestFactory
    fun `ring cases`(): List<DynamicTest> = (root["ring_cases"] as JsonArray).map { c ->
        val case = c.jsonObject
        DynamicTest.dynamicTest(case.str("name")!!) { runRingCase(case) }
    }

    private fun runRingCase(case: JsonObject) {
        val store = InMemoryCallStore()
        val doors = DoorsCache(InMemoryDoorsCacheStore(listOf(Door(ringDoorId, ringDoorRef))))
        val input = case["input"]!!.jsonObject
        val expect = case.str("expect")
        when (case.str("via")) {
            "ws" -> {
                val outcomes = ArrayList<Outcome>()
                val sink = SocketCallSink(store, doors, { now }, { _, o -> outcomes += o }, { })
                SubscribeTracker(doors).onEvent(input).forEach(sink::onCallEvent)
                if (expect == "ring") {
                    assertTrue(outcomes.single() is Outcome.Ring, "$outcomes")
                } else {
                    assertEquals(emptyList<Outcome>(), outcomes, "no outcome at all: no ring, no notification, no full-screen intent")
                    assertEquals(null, store.get(com.meda.portico.core.push.CallKey(ringDoorRef, input.str("call_id")!!)), "nothing recorded")
                }
            }
            "push" -> {
                val data = input.mapValues { it.value.jsonPrimitive.content }
                val o = Decision.receive(store, PushMessage.parse(data), now, setOf(ringDoorRef))
                assertEquals(expect, o.contractName, "$o")
                if (expect == "silent") assertEquals(null, o.update, "a dialled call leaves no record that could post a Missed call later")
            }
            else -> error("unknown via")
        }
    }
}
