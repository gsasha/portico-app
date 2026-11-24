package com.meda.portico.core.ha

import com.meda.portico.core.content.ContentCodec
import com.meda.portico.core.content.GateCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-692: the roster row's `can_dial: true` (portico-ha README "The gate as a roster endpoint and call target") is read, kept and absent otherwise. */
class RosterCanDialTest {
    private val frame = """{"id":1,"type":"result","success":true,"result":{"rev":"r1","pages":[{"id":"p","label":"P","cameras":[],"actions":[
        {"id":"g","label":"Gate","kind":"call","confirm":false,"available":true,"name":"Vehicle gate","target":{"type":"door","id":"fake:http://192.168.1.7:8091"}}]}],
        "roster":[
        {"action_id":"r-1","id":"fake:http://192.168.1.7:8091","kind":"door","name":"Vehicle gate","available":true,"reason":null,"can_dial":true},
        {"action_id":"r-2","id":"fake:vto-front.example","kind":"door","name":"Front door","available":false,"reason":"not_available"},
        {"action_id":"r-3","id":"ep-1","kind":"phone","name":"P","available":true,"can_dial":"yes"}]}}"""

    private fun profile() = (ContentCommands.parseGet(HaProtocol.parse(frame).single() as HaInbound.Result) as ContentCommands.Outcome.Profile).profile

    @Test
    fun `can_dial true is read, absent or not exactly true is false`() {
        assertEquals(listOf(true, false, false), profile().roster.map { it.canDial })
    }

    @Test
    fun `the cached document keeps can_dial through encode and decode`() {
        val back = ContentCodec.decode(ContentCodec.encode(profile()))!!
        assertEquals(profile().roster, back.roster)
        assertTrue(back.roster[0].canDial)
        assertFalse(ContentCodec.encode(profile()).substringAfter("fake:vto-front.example").substringBefore("}").contains("can_dial"), "no key for a door that cannot dial")
    }

    @Test
    fun `a parsed call button and roster make a gate call`() {
        val p = profile()
        val door = Door("http://192.168.1.7:8091", "d-gate", "fake:http://192.168.1.7:8091")
        assertEquals(GateCall("http://192.168.1.7:8091"), GateCall.of(p.actions.single(), p.roster, listOf(door)))
    }
}
