package com.meda.portico.core.call

import com.meda.portico.core.ha.CallEvent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FakeTalkLeg(val callId: String, val doorId: String, val listener: MediaListener) : TalkLeg {
    var closes = 0
    var failure: String? = null
    override fun lastFailure(): String? = failure
    var stage: String? = null
    override fun stage(): String? = stage
    override fun close() {
        closes++
    }
}

class FakeTalkMedia : TalkMediaPort {
    val legs = mutableListOf<FakeTalkLeg>()
    var opens = 0
    var failOpen = false
    override fun open(callId: String, doorId: String, listener: MediaListener): TalkLeg {
        opens++
        if (failOpen) throw IllegalStateException("no audio")
        return FakeTalkLeg(callId, doorId, listener).also { legs += it }
    }
}

/** The result of a started talk: `{ran, call_id, media: null}`. */
fun talkStarted(callId: String = TALK_CALL): JsonObject = buildJsonObject {
    put("ran", true)
    put("call_id", callId)
    put("media", JsonNull)
}

const val TALK_CALL = "hso-9-talk"
const val TALK_DOOR = "http://192.168.1.7:8091"

fun ringing(call: String = TALK_CALL, door: String = TALK_DOOR, snapshot: Boolean = false) = CallEvent.Ringing(door, "ref-$door", call, snapshot)
fun answeredEv(call: String = TALK_CALL, door: String = TALK_DOOR) = CallEvent.Answered(door, "ref-$door", call)
fun activeEv(call: String = TALK_CALL, door: String = TALK_DOOR) = CallEvent.Active(door, "ref-$door", call)
fun endedEv(call: String = TALK_CALL, door: String = TALK_DOOR, inferred: Boolean = false) = CallEvent.Ended(door, "ref-$door", call, inferred)
fun idleEv(door: String = TALK_DOOR) = CallEvent.NoLiveCall(door, "ref-$door")

/** One talk session wired to fakes. [gateOpen] is the live "is the button ready?" answer: flip it to let the server switch Talk off. */
class TalkRig(
    gateOpen: Boolean = true,
    micGranted: Boolean = true,
    doorId: String? = TALK_DOOR,
    val config: TalkConfig = TalkConfig(),
) {
    val cmds = FakeCommands()
    val media = FakeTalkMedia()
    val audio = FakeAudio()
    val sched = FakeScheduler()
    var gate = gateOpen
    var micOk = micGranted
    var wall = 7_000_000L
    val snaps = mutableListOf<TalkSnapshot>()
    val ended = mutableListOf<TalkSnapshot>()
    val ends = mutableListOf<TalkEnd>()
    var micReady = 0

    val hooks = object : TalkHooks {
        override fun onMicReady() { micReady++ }
        override fun onEnded(snapshot: TalkSnapshot) { ended += snapshot }
    }

    val request = TalkRequest("talk", "Talk", doorId)
    val s = TalkSession(request, cmds, media, audio, { micOk }, sched, { wall }, { gate }, hooks, config, onChange = { snaps += it }, onEndPath = { ends += it })

    val phase: TalkPhase get() = s.phase

    /** Everything the session put on the wire, in order. */
    fun types() = cmds.types()

    fun grant(callId: String = TALK_CALL) = cmds.last("portico/content/run").ok(talkStarted(callId))

    /** A press, the reply, the station answering and the audio flowing. */
    fun fullTalk(): FakeTalkLeg {
        s.begin()
        grant()
        s.onCallEvent(answeredEv())
        val leg = media.legs.single()
        leg.listener.onConnected()
        return leg
    }

    /** Nothing was sent, opened, entered or armed, and the session never left idle. */
    fun assertUntouched() {
        org.junit.jupiter.api.Assertions.assertEquals(emptyList<String>(), cmds.types(), "nothing sent")
        org.junit.jupiter.api.Assertions.assertEquals(0, media.opens, "no audio leg opened")
        org.junit.jupiter.api.Assertions.assertEquals(0, audio.enters, "audio route not entered")
        org.junit.jupiter.api.Assertions.assertEquals(0, micReady, "foreground service not started")
        org.junit.jupiter.api.Assertions.assertEquals(0, sched.pending, "no timer armed")
        org.junit.jupiter.api.Assertions.assertEquals(TalkPhase.IDLE, s.phase)
        org.junit.jupiter.api.Assertions.assertTrue(snaps.isEmpty() && ended.isEmpty(), "no state change")
    }
}
