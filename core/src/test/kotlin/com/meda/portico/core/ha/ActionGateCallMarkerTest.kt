package com.meda.portico.core.ha

import com.meda.portico.core.content.ContentCodec
import com.meda.portico.core.content.GateCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-695: a `call` action's `gate_call: true` (portico-ha README "A `call` button aimed at the gate") is read, kept in the cache, and absent otherwise. */
class ActionGateCallMarkerTest {
    private val gate = "fake:http://192.168.1.7:8091"
    private val door = Door("http://192.168.1.7:8091", "d-gate", gate)

    private fun frame(marker: String, kind: String = "call", roster: String = "") =
        """{"id":1,"type":"result","success":true,"result":{"rev":"r1","pages":[{"id":"p","label":"P","blocks":[{"id":"b","title":"B","cameras":[],"actions":[
        {"id":"g","label":"Gate","kind":"$kind","confirm":false,"available":true,"name":"Vehicle gate","target":{"type":"door","id":"$gate"}$marker}]}]}],
        "roster":[$roster]}}"""

    private fun profile(text: String) = (ContentCommands.parseGet(HaProtocol.parse(text).single() as HaInbound.Result) as ContentCommands.Outcome.Profile).profile

    @Test
    fun `gate_call true is read on a call button, absent or not exactly true is false`() {
        assertTrue(profile(frame(""","gate_call":true""")).actions.single().gateCall)
        assertFalse(profile(frame("")).actions.single().gateCall)
        assertFalse(profile(frame(""","gate_call":false""")).actions.single().gateCall)
        assertFalse(profile(frame(""","gate_call":"true"""")).actions.single().gateCall)
        assertFalse(profile(frame(""","gate_call":1""")).actions.single().gateCall)
    }

    @Test
    fun `the marker is only read on a call button`() {
        assertFalse(profile(frame(""","gate_call":true""", kind = "talk")).actions.single().gateCall)
        assertFalse(profile(frame(""","gate_call":true""", kind = "lock_open")).actions.single().gateCall)
    }

    @Test
    fun `the cached document keeps the marker through encode and decode, and writes no key without it`() {
        val marked = profile(frame(""","gate_call":true"""))
        val back = ContentCodec.decode(ContentCodec.encode(marked))!!
        assertTrue(back.actions.single().gateCall)
        assertEquals(marked.actions, back.actions)
        assertFalse(ContentCodec.encode(profile(frame(""))).contains("gate_call"))
        assertFalse(ContentCodec.decode(ContentCodec.encode(profile(frame(""))))!!.actions.single().gateCall)
    }

    @Test
    fun `a document from a new server with the gate hidden from the roster is a gate call, an old server's is not`() {
        val hidden = """{"action_id":"r-2","id":"fake:vto-front.example","kind":"door","name":"Front door","available":false,"reason":"not_available"}"""
        val shown = """{"action_id":"r-1","id":"$gate","kind":"door","name":"Vehicle gate","available":true,"reason":null,"can_dial":true},$hidden"""
        val new = profile(frame(""","gate_call":true""", roster = hidden))
        assertEquals(GateCall("http://192.168.1.7:8091"), GateCall.of(new.actions.single(), new.roster, listOf(door)))
        val old = profile(frame("", roster = hidden))
        assertNull(GateCall.of(old.actions.single(), old.roster, listOf(door)))
        val oldShown = profile(frame("", roster = shown))
        assertEquals(GateCall("http://192.168.1.7:8091"), GateCall.of(oldShown.actions.single(), oldShown.roster, listOf(door)), "an old server falls back to the roster row")
    }
}
