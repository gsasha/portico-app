package com.meda.portico.core.call

import com.meda.portico.core.ha.HaInbound
import com.meda.portico.core.ha.HaProtocol
import com.meda.portico.core.ha.TalkStateEvent
import com.meda.portico.core.ha.TalkWireState
import com.meda.portico.core.ha.WsContract
import com.meda.portico.core.ha.parseTalkState
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `talk_state` frames the SERVER recorded, from the vendored `ws_v3.json` (portico-ha `contract/ws_v3.json`, the three
 * "(talk to the gate)" scenarios; the file is sha256-pinned by `WsContractTest`). Every one is read through the same
 * parser the link uses ([parseTalkState]) and drives a [TalkSession] the way a phone would see it. The hand-made
 * `talk_state_cases.json` keeps only what the server never records (see its `about`).
 *
 * What the server really sends, against what the app expects, and the two documented differences:
 *  - `dialing` and a failed dial's `ended` carry `call_id: null` (the id is minted after the dial is granted), so the app's
 *    parser drops them (it needs an `out-` id). Harmless: `dialing` changes nothing, and a refused dial is told to the
 *    app by the run reply's error (`busy`), which is what ends the session.
 *  - the server never sends a `time_cap` reason (the hand-made file covers the app's mapping of it).
 */
class TalkStateServerFramesTest {

    private class Recorded(val caseName: String, val frames: List<HaInbound>) {
        val events: List<JsonObject>
            get() = frames.filterIsInstance<HaInbound.Event>().map { it.event }.filter { it["type"]?.jsonPrimitive?.contentOrNull == "talk_state" }
        val result: HaInbound.Result get() = frames.filterIsInstance<HaInbound.Result>().single()
    }

    private fun recorded(suffix: String): List<Recorded> =
        WsContract.cases.filter { it.name.endsWith(suffix) }.map { c ->
            Recorded(c.name, c.receiveFor(1).flatMap { HaProtocol.parse(it) })
        }

    private fun scenario(prefix: String) = recorded("(talk to the gate)").single { it.caseName.startsWith(prefix) }

    private val allTalkFrames: List<JsonObject> by lazy { recorded("(talk to the gate)").flatMap { it.events } }

    private val grantedId = "out-0123456789abcdef"

    @Test
    fun `the server recorded exactly these talk_state frames, all in the shape the parser reads`() {
        assertEquals(5, allTalkFrames.size, "talk_state frames in ws_v3.json (re-read the new ones before bumping this)")
        for (e in allTalkFrames) {
            assertTrue(setOf("type", "door_id", "call_id", "state", "reason").containsAll(e.keys), "unexpected keys in $e")
            assertEquals(WS_DOOR, e["door_id"]?.jsonPrimitive?.content, "$e")
            val id = e["call_id"]
            assertTrue(id == JsonNull || id!!.jsonPrimitive.content.matches(Regex("out-[0-9a-f]{16}")), "call_id is null or an out- id: $e")
            assertTrue(e["state"]!!.jsonPrimitive.content in setOf("dialing", "in_call", "ended"), "$e")
        }
        assertEquals(
            listOf("ended", "dialing", "in_call", "dialing", "ended"),
            recorded("(talk to the gate)").flatMap { it.events }.map { it["state"]!!.jsonPrimitive.content },
            "the recorded order",
        )
    }

    @Test
    fun `a recorded frame with an out- id parses to the event the server meant`() {
        val parsed = allTalkFrames.filter { it["call_id"] != JsonNull }.map { parseTalkState(it) }
        assertEquals(
            listOf(
                TalkStateEvent(WS_DOOR, grantedId, TalkWireState.ENDED, null),
                TalkStateEvent(WS_DOOR, grantedId, TalkWireState.IN_CALL, null),
            ),
            parsed,
        )
    }

    @Test
    fun `a recorded frame with call_id null is dropped by the parser, the documented difference`() {
        val nulls = allTalkFrames.filter { it["call_id"] == JsonNull }
        assertEquals(listOf("dialing", "dialing", "ended"), nulls.map { it["state"]!!.jsonPrimitive.content })
        for (e in nulls) assertNull(parseTalkState(e), "$e")
    }

    // ---- talk starts: dialing, in_call, then the reply ----------------------------------------------------------

    @Test
    fun `talk starts - dialing then in_call arrive before the run reply, the audio attaches to the out- id once the reply names it`() {
        val sc = scenario("content run: talk starts")
        val r = TalkRig()
        r.s.begin()
        for (e in sc.events) parseTalkState(e)?.let { r.s.onTalkState(it) }
        assertEquals(TalkPhase.STARTING, r.phase, "no call id yet: nothing happens before the reply")
        assertEquals(0, r.media.opens)
        val reply = sc.result
        assertTrue(reply.success)
        r.cmds.last("portico/content/run").reply(CommandReply.Reply(reply))
        assertEquals(TalkPhase.CONNECTING, r.phase)
        assertTrue(r.s.snapshot.stationAnswered, "the early in_call was replayed")
        assertEquals(1, r.media.opens)
        assertEquals(grantedId, r.media.legs.single().callId)
        assertEquals(WS_DOOR, r.media.legs.single().doorId)
        assertNull(r.s.snapshot.failure)
        assertEquals(0, r.cmds.count("portico/hangup"))
    }

    @Test
    fun `talk starts - the same frames after the reply attach the audio too`() {
        val sc = scenario("content run: talk starts")
        val r = TalkRig()
        r.s.begin()
        r.cmds.last("portico/content/run").reply(CommandReply.Reply(sc.result))
        assertEquals(TalkPhase.CONNECTING, r.phase)
        for (e in sc.events) parseTalkState(e)?.let { r.s.onTalkState(it) }
        assertTrue(r.s.snapshot.stationAnswered)
        assertEquals(1, r.media.opens)
        assertEquals(grantedId, r.media.legs.single().callId)
    }

    // ---- talk refused: dialing, ended busy (both call_id null), then the error reply -----------------------------

    @Test
    fun `talk refused - the null-id frames change nothing, the run reply's busy error ends it`() {
        val sc = scenario("content run: talk refused")
        val r = TalkRig()
        r.s.begin()
        for (e in sc.events) assertNull(parseTalkState(e), "$e")
        assertEquals(TalkPhase.STARTING, r.phase)
        assertEquals(false, sc.result.success)
        assertEquals("busy", sc.result.error!!.code)
        r.cmds.last("portico/content/run").reply(CommandReply.Reply(sc.result))
        assertEquals(TalkPhase.ENDED, r.phase)
        assertEquals(EndReason.FAILED, r.s.snapshot.endReason)
        assertEquals("GATE_BUSY", r.s.snapshot.failure?.kind?.name)
        assertEquals(0, r.media.opens)
        assertEquals(0, r.cmds.count("portico/hangup"))
        assertEquals(0, r.sched.pending, "no timer left armed")
    }

    // ---- the ended the server replays to a subscriber ------------------------------------------------------------

    private val subscribeEnded: JsonObject by lazy {
        recorded("(talk to the gate)").single { it.caseName.startsWith("calls subscribe: phone") }.events.single()
    }

    @Test
    fun `the ended on subscribe, no reason - before the station answered it is the station ending the call`() {
        val e = parseTalkState(subscribeEnded)!!
        val r = TalkRig()
        r.s.begin()
        r.grant(grantedId)
        r.s.onTalkState(e)
        assertEquals(TalkPhase.ENDED, r.phase)
        assertEquals(EndReason.FAILED, r.s.snapshot.endReason)
        assertEquals("STATION_ENDED", r.s.snapshot.failure?.kind?.name)
        assertEquals(0, r.cmds.count("portico/hangup"))
    }

    @Test
    fun `the ended on subscribe, no reason - in the call it is the gate hanging up`() {
        val e = parseTalkState(subscribeEnded)!!
        val r = TalkRig()
        r.s.begin()
        r.grant(grantedId)
        r.s.onTalkState(TalkStateEvent(WS_DOOR, grantedId, TalkWireState.IN_CALL, null))
        r.media.legs.single().listener.onConnected()
        assertEquals(TalkPhase.IN_CALL, r.phase)
        r.s.onTalkState(e)
        assertEquals(TalkPhase.ENDED, r.phase)
        assertEquals(EndReason.REMOTE_HANGUP, r.s.snapshot.endReason)
        assertNull(r.s.snapshot.failure)
        assertEquals(0, r.cmds.count("portico/hangup"))
    }

    @Test
    fun `the ended on subscribe is ignored by a session for another call and by no session at all`() {
        val e = parseTalkState(subscribeEnded)!!
        val other = TalkRig()
        other.s.begin()
        other.grant("out-fedcba9876543210")
        other.s.onTalkState(e)
        assertEquals(TalkPhase.CONNECTING, other.phase)
        assertEquals(null, other.s.snapshot.endReason)

        val none = TalkRig()
        none.s.onTalkState(e)
        none.assertUntouched()
    }

    @Test
    fun `the gate's roster key records the same three frames as the Talk button, and the app reads them the same way`() {
        val c = recorded("(gate as a call target)").single { it.caseName.startsWith("content run: the gate's roster key") }
        assertEquals(listOf("dialing", "in_call", "ended"), c.events.map { it["state"]!!.jsonPrimitive.content })
        assertEquals(
            listOf(null, TalkStateEvent(WS_DOOR, grantedId, TalkWireState.IN_CALL, null), TalkStateEvent(WS_DOOR, grantedId, TalkWireState.ENDED, null)),
            c.events.map { parseTalkState(it) },
            "dialing carries no call id and is dropped; the rest are the dialled call's",
        )
    }

    private companion object {
        const val WS_DOOR = "http://192.168.1.7:8091"
    }
}
