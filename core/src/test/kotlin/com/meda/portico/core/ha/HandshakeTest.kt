package com.meda.portico.core.ha

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `portico/version` and `portico/doors` (contract 3) on hand-made frames: the edges the recorded file does not hold. */
class HandshakeTest {

    private fun result(json: String): HaInbound.Result = HaProtocol.parse(json).single() as HaInbound.Result
    private fun ok(body: String) = result("""{"id":1,"type":"result","success":true,"result":$body}""")
    private fun err(code: String) = result("""{"id":1,"type":"result","success":false,"error":{"code":"$code","message":"m"}}""")

    @Test
    fun `only contract 3 is accepted`() {
        assertEquals(Handshake.Version.Ok(1), Handshake.parseVersion(ok("""{"contract":3,"ui_version":1,"providers":[]}""")))
        assertEquals(Handshake.Version.Ok(null), Handshake.parseVersion(ok("""{"contract":3}""")), "the ui version is for display only")
        assertEquals(3, Handshake.CONTRACT)
    }

    @Test
    fun `any other number is a mismatch that names the server's number`() {
        for (n in listOf(0, 1, 2, 4, 99)) {
            val v = Handshake.parseVersion(ok("""{"contract":$n}""")) as Handshake.Version.Mismatch
            assertEquals(ContractMismatch(n, "contract"), v.mismatch, "contract $n")
        }
    }

    @Test
    fun `a failure or an unreadable result is a mismatch with no number`() {
        for (r in listOf(err("unknown_command"), err("unauthorized"), ok("null"), ok("[]"), ok("""{"contract":"3"}"""), ok("""{"contract":3.5}"""), ok("{}"))) {
            val v = Handshake.parseVersion(r) as Handshake.Version.Mismatch
            assertNull(v.mismatch.serverContract, r.toString())
        }
        assertEquals("unknown_command", (Handshake.parseVersion(err("unknown_command")) as Handshake.Version.Mismatch).mismatch.code)
        assertEquals("malformed_result", (Handshake.parseVersion(ok("null")) as Handshake.Version.Mismatch).mismatch.code)
    }

    @Test
    fun `the mismatch text never holds a URL, a token or a message`() {
        val text = ContractMismatch(2, "contract").userText
        assertEquals("This Home Assistant runs a different Portico version (server contract 2, app needs 3); retrying.", text)
        assertFalse(text.contains("http") || text.contains("token"))
    }

    @Test
    fun `doors keep the ids, the driver and the name, and tolerate every other field`() {
        val doors = Handshake.parseDoors(
            ok(
                """{"doors":[{"door_id":"d1","door_ref":"d-1","endpoint_id":"p:d1","name":"Gate","driver":"gate_sip_observer",
                "capabilities":{"HasRelay":true},"camera_entity_id":"camera.gate","available":false,"away_media":true,"a_future_field":[1,2]},
                {"door_id":"d2","door_ref":"d-2"}]}""",
            ),
        )
        assertEquals(listOf(Door("d1", "d-1", "p:d1", "gate_sip_observer", "Gate"), Door("d2", "d-2")), doors)
    }

    @Test
    fun `answerable false is kept, absent means answerable, and it survives the cache codec`() {
        val doors = Handshake.parseDoors(
            ok("""{"doors":[{"door_id":"f","door_ref":"d-f","answerable":false},{"door_id":"g","door_ref":"d-g","answerable":true},{"door_id":"h","door_ref":"d-h"}]}"""),
        )!!
        assertEquals(listOf(false, true, true), doors.map { it.answerable })
        assertEquals(doors, DoorCodec.decode(DoorCodec.encode(doors)))
    }

    @Test
    fun `a door without its id or its ref is skipped, a non-list is null`() {
        assertEquals(
            listOf(Door("d2", "d-2")),
            Handshake.parseDoors(ok("""{"doors":[{"door_id":"d1"},{"door_ref":"d-9"},7,{"door_id":"d2","door_ref":"d-2"}]}""")),
        )
        assertEquals(emptyList<Door>(), Handshake.parseDoors(ok("""{"doors":[]}""")))
        for (r in listOf(ok("[]"), ok("null"), ok("{}"), ok("""{"devices":[{"device_id":"x","entry_id":"y"}]}"""), err("unauthorized"))) {
            assertNull(Handshake.parseDoors(r), r.toString())
        }
    }

    @Test
    fun `contract 2's device_id and entry_id do not make a door`() {
        assertTrue(Handshake.parseDoors(ok("""{"doors":[{"device_id":"x","entry_id":"y","driver":"dahua_sip"}]}"""))!!.isEmpty())
    }
}
