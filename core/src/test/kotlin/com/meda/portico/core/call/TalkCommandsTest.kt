package com.meda.portico.core.call

import com.meda.portico.core.ha.HaError
import com.meda.portico.core.ha.HaInbound
import com.meda.portico.core.ha.WsContract
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class TalkCommandsTest {

    private fun ok(r: JsonElement?) = HaInbound.Result(1, true, r, null)
    private fun err(code: String) = HaInbound.Result(1, false, null, HaError(code, ""))

    @Test
    fun `a talk adds no command - run, hangup and webrtc offer are all in the app's recorded command list`() {
        val commands = WsContract.appCommands
        assertEquals(listOf("portico/content/run", "portico/hangup", "portico/webrtc_offer"), listOf(TalkCommands.RUN, TalkCommands.HANGUP, AnswerCommands.WEBRTC_OFFER))
        for (c in listOf(TalkCommands.RUN, TalkCommands.HANGUP, AnswerCommands.WEBRTC_OFFER)) assertTrue(c in commands, c)
        assertEquals(27, commands.size, "the sixteen commands of contract 3, the five portico/house commands of M7 3b-A, portico/connlog/report (DEV-705), the three live commands of DEV-713 S0 portico/wall/heartbeat (DEV-807) and portico/panel/call_result (D-20261121-c98a)")
        assertEquals("portico/hangup", PlaceCommands.HANGUP, "one hang-up for every kind of call")
    }

    @Test
    fun `the command list file is the 3a server list plus connlog report - twenty-seven sorted lines, none of them a dial`() {
        val lines = File("src/test/resources/app_commands_v3.txt").readLines().filter { it.isNotBlank() }
        assertEquals(27, lines.size)
        assertEquals(lines.sorted(), lines)
        assertTrue("portico/dial" !in lines, "the app never sends portico/dial")
    }

    @Test
    fun `no source of the app builds a dial or a talk command of its own`() {
        val main = File("src/main/kotlin")
        for (f in main.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            assertTrue(!f.readText().contains("\"portico/dial\""), f.name)
        }
    }

    @Test
    fun `the start sends the action id and nothing else`() {
        assertEquals(buildJsonObject { put("action_id", "talk") }, TalkCommands.start("talk"))
    }

    @Test
    fun `the hang-up is the call id and a reason, and a blank of either is refused before the wire`() {
        assertEquals(buildJsonObject { put("call_id", "c1"); put("reason", "hangup") }, TalkCommands.hangup("c1", "hangup"))
        assertThrows(IllegalArgumentException::class.java) { TalkCommands.hangup("", "hangup") }
        assertThrows(IllegalArgumentException::class.java) { TalkCommands.hangup("c1", "") }
    }

    @Test
    fun `a granted start carries a call id, ran true, and no media`() {
        val g = TalkCommands.parseStarted(ok(talkStarted("abc"))) as TalkCommands.Started.Granted
        assertEquals("abc", g.callId)
    }

    @Test
    fun `a start without ran, without a call id or with an empty one is refused`() {
        for (body in listOf(
            buildJsonObject { put("ran", true) },
            buildJsonObject { put("ran", true); put("call_id", "") },
            buildJsonObject { put("ran", false); put("call_id", "x") },
            buildJsonObject { put("call_id", "x") },
        )) {
            val r = TalkCommands.parseStarted(ok(body)) as TalkCommands.Started.Refused
            assertEquals("malformed_result", r.failure.code)
        }
        assertTrue(TalkCommands.parseStarted(ok(null)) is TalkCommands.Started.Refused)
        assertTrue(TalkCommands.parseStarted(ok(kotlinx.serialization.json.JsonPrimitive("x"))) is TalkCommands.Started.Refused)
    }

    @Test
    fun `an error reply is the server's code mapped, and a reply with no error is unknown`() {
        val r = TalkCommands.parseStarted(err("busy")) as TalkCommands.Started.Refused
        assertEquals(TalkFailure.Kind.GATE_BUSY, r.failure.kind)
        val u = TalkCommands.parseStarted(HaInbound.Result(1, false, null, null)) as TalkCommands.Started.Refused
        assertEquals("unknown_error", u.failure.code)
    }

    @Test
    fun `the recorded refusals of a Talk press parse to their own kinds`() {
        fun refused(name: String) = TalkCommands.parseStarted(com.meda.portico.core.ha.HaProtocol.parse(WsContract.reply(name, 1)).single() as HaInbound.Result) as TalkCommands.Started.Refused
        val off = refused("blocks, content run: talk is in the grant and refused (switched off)")
        assertEquals(TalkFailure.Kind.SWITCHED_OFF, off.failure.kind)
        assertEquals("not_available", off.failure.code)
        assertEquals(TalkFailure.Kind.NOT_ALLOWED, refused("blocks, content run: talk by an administrator naming the phone is not_allowed").failure.kind)
    }
}
