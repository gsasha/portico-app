package com.meda.portico.core.call

import com.meda.portico.core.ha.HaError
import com.meda.portico.core.ha.HaInbound
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The five `portico/house/...` builders and parsers (M7 3b-A). The recorded server frames are replayed in `WsContractReplayTest`. */
class HouseCommandsTest {

    private fun ok(result: kotlinx.serialization.json.JsonElement? = buildJsonObject {}) = HaInbound.Result(1, true, result, null)
    private fun err(code: String) = HaInbound.Result(1, false, null, HaError(code, "text the app never reads"))

    @Test
    fun `the command names are the server's`() {
        assertEquals("portico/house/answer", HouseCommands.ANSWER)
        assertEquals("portico/house/decline", HouseCommands.DECLINE)
        assertEquals("portico/house/hangup", HouseCommands.HANGUP)
        assertEquals("portico/house/presence", HouseCommands.PRESENCE)
        assertEquals("portico/house/media_open", HouseCommands.MEDIA_OPEN)
    }

    @Test
    fun `an answer carries the call id and client kind app, nothing else`() {
        val b = HouseCommands.answer("hso-1")
        assertEquals(setOf("call_id", "client_kind"), b.keys)
        assertEquals("app", (b["client_kind"] as JsonPrimitive).content)
    }

    @Test
    fun `decline and hangup carry the call id alone (the server's schema refuses another key)`() {
        assertEquals(setOf("call_id"), HouseCommands.decline("hso-1").keys)
        assertEquals(setOf("call_id"), HouseCommands.hangup("hso-1").keys)
    }

    @Test
    fun `presence carries the Do Not Disturb flag alone, true and false`() {
        assertEquals("true", (HouseCommands.presence(true)["dnd"] as JsonPrimitive).content)
        assertEquals("false", (HouseCommands.presence(false)["dnd"] as JsonPrimitive).content)
        assertEquals(setOf("dnd"), HouseCommands.presence(true).keys)
    }

    @Test
    fun `media_open is a frame channel with a codec, or a WebRTC offer with an sdp - never both`() {
        val frames = HouseCommands.mediaOpen("hso-1")
        assertEquals(setOf("call_id", "codec"), frames.keys)
        assertEquals("pcmu", (frames["codec"] as JsonPrimitive).content)
        val rtc = HouseCommands.mediaOpen("hso-1", sdp = "v=0")
        assertEquals(setOf("call_id", "sdp"), rtc.keys)
    }

    @Test
    fun `a blank call id is refused before the wire`() {
        assertThrows<IllegalArgumentException> { HouseCommands.answer("") }
        assertThrows<IllegalArgumentException> { HouseCommands.decline("") }
        assertThrows<IllegalArgumentException> { HouseCommands.hangup("") }
        assertThrows<IllegalArgumentException> { HouseCommands.mediaOpen("") }
    }

    @Test
    fun `the id picks the hang-up command - a house call never goes to the door's portico hangup`() {
        assertTrue(HouseCommands.isHouseCall("hso-1759-1-a"))
        assertFalse(HouseCommands.isHouseCall("gso-1759-1-a"))
        assertFalse(HouseCommands.isHouseCall("out-0123"))
        assertFalse(HouseCommands.isHouseCall(null))
        val (houseType, houseBody) = HouseCommands.hangupFor("hso-1", "hangup")
        assertEquals("portico/house/hangup", houseType)
        assertEquals(setOf("call_id"), houseBody.keys)
        val (doorType, doorBody) = HouseCommands.hangupFor("gso-1", "cancelled")
        assertEquals("portico/hangup", doorType)
        assertEquals(setOf("call_id", "reason"), doorBody.keys)
    }

    @Test
    fun `answer results - granted keeps the media descriptor opaque, anything else is a worded refusal`() {
        val granted = HouseCommands.parseAnswer(ok(buildJsonObject { put("call_id", "hso-1"); put("media", buildJsonObject { put("kind", "x") }) }))
        assertEquals("hso-1", (granted as HouseCommands.Answered.Granted).callId)
        assertEquals("x", ((granted.media as JsonObject)["kind"] as JsonPrimitive).content)
        assertNull((HouseCommands.parseAnswer(ok(buildJsonObject { put("call_id", "hso-1") })) as HouseCommands.Answered.Granted).media)
        val noId = HouseCommands.parseAnswer(ok(buildJsonObject {})) as HouseCommands.Answered.Refused
        assertEquals("malformed_result", noId.failure.code)
        val taken = HouseCommands.parseAnswer(err("already_answered")) as HouseCommands.Answered.Refused
        assertEquals(PlaceFailure.Kind.ALREADY_ANSWERED, taken.failure.kind)
    }

    @Test
    fun `plain results - success is ok, an error keeps the server's code`() {
        assertEquals(HouseCommands.Plain.Ok, HouseCommands.parsePlain(ok()))
        val r = HouseCommands.parsePlain(err("unknown_call")) as HouseCommands.Plain.Refused
        assertEquals("unknown_call", r.failure.code)
    }

    @Test
    fun `media_open results - frames with their shape, webrtc, or a refusal`() {
        val frames = HouseCommands.parseMediaOpen(ok(buildJsonObject { put("kind", "frames"); put("codec", "pcmu"); put("frame_bytes", 320); put("frame_ms", 40) }))
        assertEquals(HouseCommands.Opened.Frames("pcmu", 320, 40), frames)
        assertEquals(HouseCommands.Opened.WebRtc, HouseCommands.parseMediaOpen(ok(buildJsonObject { put("kind", "webrtc") })))
        assertTrue(HouseCommands.parseMediaOpen(ok(buildJsonObject { put("kind", "frames") })) is HouseCommands.Opened.Refused, "frames without its shape")
        assertTrue(HouseCommands.parseMediaOpen(ok(buildJsonObject { put("kind", "carrier-pigeon") })) is HouseCommands.Opened.Refused)
        val noBridge = HouseCommands.parseMediaOpen(err("not_supported")) as HouseCommands.Opened.Refused
        assertEquals(PlaceFailure.Kind.NO_AUDIO_BRIDGE, noBridge.failure.kind)
    }
}
