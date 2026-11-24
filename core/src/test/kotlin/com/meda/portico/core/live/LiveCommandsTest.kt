package com.meda.portico.core.live

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.HaInbound
import com.meda.portico.core.ha.HaProtocol
import com.meda.portico.core.ha.LiveCandidate
import com.meda.portico.core.ha.LiveCommands
import com.meda.portico.core.push.MiniJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The wire shapes of the three live commands and the parsers of their answers (the vendored cases are replayed in `WsContractReplayTest`). */
class LiveCommandsTest {
    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject
    private fun result(text: String) = HaProtocol.parse(text).single() as HaInbound.Result
    private fun event(text: String) = (HaProtocol.parse(text).single() as HaInbound.Event).event

    @Test
    fun `live names the camera, the offer and the size, and an endpoint only when given`() {
        assertEquals(obj("""{"camera_id":"gate","sdp":"v=0 x","size":"big"}"""), LiveCommands.live("gate", "v=0 x", CameraSize.BIG))
        assertEquals(obj("""{"camera_id":"gate","sdp":"v=0 x","size":"small"}"""), LiveCommands.live("gate", "v=0 x", CameraSize.SMALL))
        assertEquals(obj("""{"camera_id":"gate","sdp":"v=0 x"}"""), LiveCommands.live("gate", "v=0 x"))
        assertEquals(obj("""{"camera_id":"p","sdp":"v=0 x","endpoint":"kitchen"}"""), LiveCommands.live("p", "v=0 x", endpoint = "kitchen"))
    }

    @Test
    fun `the builder refuses what the server would, and any offer with an audio line`() {
        assertThrows<IllegalArgumentException> { LiveCommands.live("", "v=0") }
        assertThrows<IllegalArgumentException> { LiveCommands.live("x".repeat(65), "v=0") }
        assertThrows<IllegalArgumentException> { LiveCommands.live("gate", "") }
        assertThrows<IllegalArgumentException> { LiveCommands.live("gate", "v".repeat(LiveCommands.MAX_SDP_CHARS + 1)) }
        assertThrows<IllegalArgumentException> { LiveCommands.live("gate", "v=0\r\nm=video 9\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n") }
        assertThrows<IllegalArgumentException> { LiveCommands.live("gate", "m=audio 9 RTP/AVP 0") }
        LiveCommands.live("gate", "v".repeat(LiveCommands.MAX_SDP_CHARS)) // the limit itself is fine
        assertTrue(LiveCommands.hasAudioLine("v=0\r\nm=audio 9"))
        assertFalse(LiveCommands.hasAudioLine("v=0\r\nm=video 9\r\na=group:BUNDLE audio-less"), "a word in another line is not an audio line")
    }

    @Test
    fun `live_ice carries a candidate with its optional keys, or the end mark`() {
        assertEquals(
            obj("""{"session_id":"live-1","candidate":{"candidate":"candidate:7 1 UDP 1 192.168.2.28 52000 typ host","sdpMid":"0","sdpMLineIndex":0}}"""),
            LiveCommands.ice("live-1", LiveCandidate("candidate:7 1 UDP 1 192.168.2.28 52000 typ host", "0", 0)),
        )
        assertEquals(obj("""{"session_id":"live-1","candidate":{"candidate":""}}"""), LiveCommands.ice("live-1", LiveCandidate.END))
        assertTrue(LiveCandidate.END.isEnd)
        assertFalse(LiveCandidate("candidate:1").isEnd)
        assertThrows<IllegalArgumentException> { LiveCommands.ice("", LiveCandidate.END) }
    }

    @Test
    fun `live_close names the session`() {
        assertEquals(obj("""{"session_id":"live-1"}"""), LiveCommands.close("live-1"))
        assertThrows<IllegalArgumentException> { LiveCommands.close("") }
    }

    @Test
    fun `a candidate never prints its line`() {
        assertFalse("10.2" in LiveCandidate("candidate:7 1 UDP 1 192.168.2.28 52000 typ host").toString())
        assertFalse("v=0" in LiveCommands.Event.Answer("v=0 secret").toString())
    }

    @Test
    fun `the result is read into an opened session, a refusal, nothing usable or a lost socket`() {
        assertEquals(LiveCommands.Reply.Opened("live-1", "main"), LiveCommands.parseReply(result("""{"id":1,"type":"result","success":true,"result":{"session_id":"live-1","stream":"main"}}""")))
        assertEquals(LiveCommands.Reply.Opened("live-2", null), LiveCommands.parseReply(result("""{"id":1,"type":"result","success":true,"result":{"session_id":"live-2"}}""")))
        assertEquals(LiveCommands.Reply.Refused("too_many_live"), LiveCommands.parseReply(result("""{"id":1,"type":"result","success":false,"error":{"code":"too_many_live","message":"m"}}""")))
        assertEquals(LiveCommands.Reply.Malformed, LiveCommands.parseReply(result("""{"id":1,"type":"result","success":true,"result":{}}""")))
        assertEquals(LiveCommands.Reply.Malformed, LiveCommands.parseReply(result("""{"id":1,"type":"result","success":true,"result":null}""")))
        assertEquals(LiveCommands.Reply.Lost, LiveCommands.parseReply(null))
    }

    @Test
    fun `the events are read in the card's keys, and anything else is ignored`() {
        assertEquals(LiveCommands.Event.Session("live-1", "sub"), LiveCommands.parseEvent(event("""{"id":1,"type":"event","event":{"type":"session","session_id":"live-1","stream":"sub"}}""")))
        assertEquals(
            LiveCommands.Event.Candidate(LiveCandidate("candidate:1 1 UDP 1 192.168.1.5 8555 typ host", "0", null)),
            LiveCommands.parseEvent(event("""{"id":1,"type":"event","event":{"type":"candidate","candidate":{"candidate":"candidate:1 1 UDP 1 192.168.1.5 8555 typ host","sdpMid":"0"}}}""")),
        )
        assertEquals(
            LiveCommands.Event.Candidate(LiveCandidate("candidate:2", null, 1)),
            LiveCommands.parseEvent(event("""{"id":1,"type":"event","event":{"type":"candidate","candidate":{"candidate":"candidate:2","sdpMLineIndex":1}}}""")),
        )
        assertEquals(LiveCommands.Event.Error("live_unavailable"), LiveCommands.parseEvent(event("""{"id":1,"type":"event","event":{"type":"error","code":"live_unavailable","message":"m"}}""")))
        val answer = LiveCommands.parseEvent(event("""{"id":1,"type":"event","event":{"type":"answer","sdp":"v=0 A"}}""")) as LiveCommands.Event.Answer
        assertEquals("v=0 A", answer.sdp)
        for (other in listOf(
            """{"type":"future"}""", """{"type":"session"}""", """{"type":"answer"}""", """{"type":"candidate"}""", """{"type":"candidate","candidate":{"candidate":""}}""",
            """{"type":"candidate","candidate":"x"}""", """{}""",
        )) assertEquals(LiveCommands.Event.Other, LiveCommands.parseEvent(obj(other)), other)
        assertEquals(LiveCommands.Event.Error("unknown_error"), LiveCommands.parseEvent(obj("""{"type":"error"}""")))
    }

    @Test
    fun `the codes read as the notice classes`() {
        assertEquals(LiveWhy.TOO_MANY, LiveWhy.ofCode("too_many_live"))
        assertEquals(LiveWhy.NO_LIVE, LiveWhy.ofCode("live_unavailable"))
        assertEquals(LiveWhy.NO_LIVE, LiveWhy.ofCode("unavailable"))
        for (c in listOf("unauthorized", "unknown_endpoint", "unknown_camera", "invalid_format", "unknown_command", "whatever")) assertEquals(LiveWhy.SERVER, LiveWhy.ofCode(c), c)
    }

    @Test
    fun `the builders put nothing on the wire but the keys of the contract`() {
        val keys = (MiniJson.parse(LiveCommands.live("gate", "v=0", CameraSize.BIG).toString()) as Map<*, *>).keys
        assertEquals(setOf("camera_id", "sdp", "size"), keys)
    }
}
