package com.meda.portico.core.call

import com.meda.portico.core.ha.HaInbound
import com.meda.portico.core.ha.HaProtocol
import com.meda.portico.core.ha.WsContract
import com.meda.portico.core.push.MiniJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** The vendored `ws_media_v2.json`: the away-call-audio exchange, recorded from the integration's handlers (contract 3). */
object WsMediaContract {
    class Step(val send: Map<String, Any?>?, val notify: Map<String, Any?>?, val recv: List<Map<String, Any?>>) {
        val sendType: String? get() = send?.get("type") as String?
    }

    class MediaCase(val name: String, val flag: Boolean, val steps: List<Step>) {
        override fun toString() = name
    }

    @Suppress("UNCHECKED_CAST")
    fun root(): Map<String, Any?> = MiniJson.parse(String(WsContract.resourceBytes(WsContract.MEDIA_RESOURCE), Charsets.UTF_8)) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val cases: List<MediaCase> by lazy {
        (root()["cases"] as List<Map<String, Any?>>).map { c ->
            MediaCase(
                c["name"] as String, c["flag"] as Boolean,
                (c["steps"] as List<Map<String, Any?>>).map { Step(it["send"] as Map<String, Any?>?, it["notify"] as Map<String, Any?>?, it["recv"] as List<Map<String, Any?>>) },
            )
        }
    }

    fun case(name: String): MediaCase = cases.first { it.name == name }
}

/**
 * Replays `ws_media_v2.json` through the app's media builders and parsers: the requests the app builds equal the file's
 * (`door_id`, not v1's `device_id`), and the recorded replies and events give the typed results the answer path acts on.
 */
class WsMediaContractTest {

    private fun body(send: Map<String, Any?>): JsonObject = Json.parseToJsonElement(WsContract.encode(send - "id" - "type")).let { it as JsonObject }

    private fun result(recv: Map<String, Any?>): HaInbound.Result = HaProtocol.parse(WsContract.encode(recv)).single() as HaInbound.Result

    @Test
    fun theFileHasTheFiveCasesAndTheirShape() {
        assertEquals(5, WsMediaContract.cases.size)
        assertEquals("ws_media", WsMediaContract.root()["contract"])
        assertEquals("2.0", WsMediaContract.root()["version"])
        for (c in WsMediaContract.cases) for (s in c.steps) {
            assertTrue((s.send != null) != (s.notify != null), "a step is a client message or a transport notification: ${c.name}")
        }
    }

    /** Every client message in the file is one the app builds, field for field. */
    @TestFactory
    fun everyRequestIsTheOneTheAppBuilds(): List<DynamicTest> = WsMediaContract.cases.flatMap { c ->
        c.steps.mapNotNull { it.send }.map { send ->
            DynamicTest.dynamicTest("${c.name}: ${send["type"]}") {
                val built = when (val type = send["type"] as String) {
                    AnswerCommands.ANSWER -> AnswerCommands.answer(send["door_id"] as String, send["call_id"] as String)
                    MediaCommands.OPEN -> MediaCommands.open(send["door_id"] as String, send["call_id"] as String, send["codec"] as String)
                    MediaCommands.FRAME -> MediaCommands.frame(send["call_id"] as String, send["d"] as String)
                    MediaCommands.CLOSE -> MediaCommands.close(send["call_id"] as String)
                    else -> throw AssertionError("the app does not build $type")
                }
                assertEquals(body(send), built, "${c.name}: ${send["type"]}")
            }
        }
    }

    @Test
    fun theFullExchangeParsesTheWayTheAnswerPathNeeds() {
        val steps = WsMediaContract.case("open, downlink frame, uplink frame, phone closes").steps
        assertEquals(AnswerCommands.Claim.Granted("relay"), AnswerCommands.parseClaim(result(steps[0].recv.single())))
        assertEquals(MediaCommands.Opened.Ok("pcmu"), MediaCommands.parseOpen(result(steps[1].recv.single()), AudioProfile.PCMU))
        val got = mutableListOf<String>()
        val listener = object : FrameListener {
            override fun onFrame(payloadBase64: String) { got += "frame:$payloadBase64" }
            override fun onEnd(reason: String) { got += "end:$reason" }
            override fun onLost() { got += "lost" }
        }
        // a downlink frame is an event on the open command; the end after the phone's close is one too
        @Suppress("UNCHECKED_CAST")
        val frame = HaProtocol.parse(WsContract.encode(steps[2].recv.single())).single() as HaInbound.Event
        MediaCommands.dispatch(frame.event, listener)
        @Suppress("UNCHECKED_CAST")
        val end = HaProtocol.parse(WsContract.encode(steps[4].recv.first())).single() as HaInbound.Event
        MediaCommands.dispatch(end.event, listener)
        assertEquals(listOf("frame:QUJD", "end:closed_by_phone"), got)
    }

    @Test
    fun theTransportEndingTheLegIsAnEndWithItsReason() {
        val step = WsMediaContract.case("the transport reports the leg gone (no uplink for 5 s)").steps[2]
        val got = mutableListOf<String>()
        val listener = object : FrameListener {
            override fun onFrame(payloadBase64: String) {}
            override fun onEnd(reason: String) { got += reason }
            override fun onLost() {}
        }
        MediaCommands.dispatch((HaProtocol.parse(WsContract.encode(step.recv.single())).single() as HaInbound.Event).event, listener)
        assertEquals(listOf("idle"), got)
    }

    @Test
    fun theRefusalsMapToTheAnswerPathsFailures() {
        val off = WsMediaContract.case("flag off: refused, nothing opened").steps[1]
        val offOpened = MediaCommands.parseOpen(result(off.recv.single()), AudioProfile.PCMU) as MediaCommands.Opened.Refused
        assertEquals(AnswerFailure.Kind.MEDIA, offOpened.failure.kind)
        assertEquals("away_media_disabled", offOpened.failure.code)

        val steps = WsMediaContract.case("refusals: unclaimed call, unknown device, second open").steps
        val unknownCall = MediaCommands.parseOpen(result(steps[0].recv.single()), AudioProfile.PCMU) as MediaCommands.Opened.Refused
        assertEquals(AnswerFailure.Kind.OTHER, unknownCall.failure.kind)
        assertEquals("unknown_call", unknownCall.failure.code)
        val unknownDoor = MediaCommands.parseOpen(result(steps[1].recv.single()), AudioProfile.PCMU) as MediaCommands.Opened.Refused
        assertEquals(AnswerFailure.Kind.UNKNOWN_DOOR, unknownDoor.failure.kind, "contract 3 says unknown_door (v1: unknown_device)")
        assertEquals(MediaCommands.Opened.Ok("pcmu"), MediaCommands.parseOpen(result(steps[3].recv.single()), AudioProfile.PCMU))
        val second = MediaCommands.parseOpen(result(steps[4].recv.single()), AudioProfile.PCMU) as MediaCommands.Opened.Refused
        assertEquals("already_open", second.failure.code)
    }
}
