package com.meda.portico.core.call

import com.meda.portico.core.ha.CallEvent
import com.meda.portico.core.ha.Door
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.InMemoryDoorsCacheStore
import com.meda.portico.core.push.Decision
import com.meda.portico.core.push.InMemoryCallStore
import com.meda.portico.core.push.SocketEvents
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * GOLDEN: the single-call flows of the answer path produce the same events and commands as before M7 H1. The expected text
 * (`golden_single_call_v1.txt`) was recorded from the code of origin/main at 0.5.10-alpha (this test, minus the `holdEnabled`
 * argument, run against that tree); here every flow runs with the hold gate OFF (the shipped setting) and ON, and both must
 * equal it. It uses only what the pre-hold fakes had, and prints states by hand, so it compiled and ran against the old tree.
 */
class GoldenSingleCallTest {

    private class Rig(holdEnabled: Boolean) {
        val cmds = FakeCommands()
        val media = FakeMedia()
        val audio = FakeAudio()
        val sched = FakeScheduler()
        val active = InMemoryActiveCallStore()
        val store = InMemoryCallStore()
        val doors = DoorsCache(InMemoryDoorsCacheStore(listOf(Door("gate", REF, driver = "gate_sip_observer", name = "Gate"))))
        var mic = true
        var now = 1_000_000L
        val out = mutableListOf<String>()
        var starts = 0
        var stops = 0

        val c = AnswerCoordinator(
            newSession = { callId, doorId, hooks, onChange ->
                AnswerSession(callId, doorId, cmds, SerialMedia(media) { it() }, audio, { mic }, sched, active, { now }, hooks, AnswerConfig(), onChange = onChange)
            },
            callStore = store,
            doors = doors,
            sweep = AnswerSweep(cmds, active),
            post = { it() },
            stopRing = { door, call, why -> out += "stopRing $door/$call/$why" },
            serviceStart = { starts++; out += "serviceStart" },
            serviceStop = { stops++; out += "serviceStop" },
            paused = { false },
            wallClockMs = { now },
            beforeBegin = { out += "beforeBegin" },
            holdEnabled = holdEnabled,
        )

        init {
            c.addListener { s ->
                val f = s.snapshot.failure
                out += "state key=${s.key?.callId} gen=${s.generation} phase=${s.snapshot.phase} end=${s.snapshot.endReason} " +
                    "fail=${f?.code}/${f?.kind}/${f?.message}/${f?.dismissRing}/${f?.retryable} muted=${s.snapshot.muted} at=${s.snapshot.connectedAtMs}"
            }
        }

        fun ring(call: String) {
            Decision.receive(store, SocketEvents.ringing(REF, call, now), now, setOf(REF))
        }

        fun connect(call: String): FakeMediaCall {
            c.press(REF, call)
            val m = media.calls.last()
            m.offerCb!!("v=0 offer")
            cmds.last("portico/answer").ok(obj("mode" to "relay"))
            cmds.last("portico/webrtc_offer").ok(obj("sdp" to "v=0 answer"))
            m.answerCb!!(true)
            m.listener.onConnected()
            return m
        }

        fun finish(label: String): String {
            val sb = StringBuilder("== $label\n")
            for (l in out) sb.append(l).append('\n')
            for (cmd in cmds.sent) sb.append("cmd ${cmd.type} ${cmd.body} answered=${cmd.answered}\n")
            sb.append("media calls=${media.calls.size} closes=${media.calls.map { it.closes }} micOff=${media.calls.map { it.micOff }}\n")
            sb.append("audio enters=${audio.enters} leaves=${audio.leaves}\n")
            sb.append("service starts=$starts stops=$stops pendingTimers=${sched.pending}\n")
            sb.append("activeStore=${active.load()}\n")
            sb.append("callStore=${store.get(com.meda.portico.core.push.CallKey(REF, CALL))?.state}\n")
            return sb.toString()
        }
    }

    private fun flows(holdEnabled: Boolean): String {
        val sb = StringBuilder()

        run {
            val r = Rig(holdEnabled)
            r.ring(CALL)
            r.out += "canAnswer=${r.c.canAnswer(REF, CALL)}"
            r.connect(CALL)
            r.c.setMuted(true)
            r.c.setMuted(false)
            r.now += 5_000
            r.c.hangUp()
            r.cmds.last("portico/hangup").ok()
            r.out += "canAnswerAfter=${r.c.canAnswer(REF, CALL)}"
            sb.append(r.finish("answer, mute, unmute, hang up"))
        }
        run {
            val r = Rig(holdEnabled)
            r.ring(CALL)
            r.c.press(REF, CALL)
            r.media.calls.last().offerCb!!("v=0 offer")
            r.cmds.last("portico/answer").error("already_answered")
            r.c.press(REF, CALL)
            r.out += "canAnswerAfter=${r.c.canAnswer(REF, CALL)}"
            sb.append(r.finish("claim refused: already answered, then a press"))
        }
        run {
            val r = Rig(holdEnabled)
            r.ring(CALL)
            r.connect(CALL)
            r.c.onCallEvent(CallEvent.Ended("gate", REF, CALL, inferred = false))
            sb.append(r.finish("the door ends the call"))
        }
        run {
            val r = Rig(holdEnabled)
            r.ring(CALL)
            r.connect(CALL)
            r.c.onCallEvent(CallEvent.NoLiveCall("gate", REF))
            sb.append(r.finish("the door goes idle"))
        }
        run {
            val r = Rig(holdEnabled)
            r.ring(CALL)
            r.connect(CALL)
            r.c.onLinkLost()
            r.sched.advance(8_000)
            r.cmds.last("portico/hangup").lost()
            r.c.onLinkReady()
            r.cmds.sent.last { it.type == "portico/hangup" && !it.answered }.ok()
            sb.append(r.finish("link lost past the grace, hang-up owed, then sent"))
        }
        run {
            val r = Rig(holdEnabled)
            r.ring(CALL)
            r.connect(CALL)
            r.c.onLinkLost()
            r.sched.advance(3_000)
            r.c.onLinkReady()
            r.sched.advance(60_000)
            sb.append(r.finish("link lost and back inside the grace"))
        }
        run {
            val r = Rig(holdEnabled)
            r.ring(CALL)
            r.c.press(REF, CALL)
            r.media.calls.last().offerCb!!("v=0 offer")
            r.c.hangUp() // while CLAIMING
            r.cmds.last("portico/answer").ok(obj("mode" to "relay"))
            sb.append(r.finish("hang up while the claim is in flight"))
        }
        run {
            val r = Rig(holdEnabled)
            r.ring(CALL)
            r.c.press(REF, CALL)
            r.media.calls.last().offerCb!!("v=0 offer")
            r.cmds.last("portico/answer").error("device_busy")
            r.c.press(REF, CALL) // the retry is a new press
            r.media.calls.last().offerCb!!("v=0 offer")
            r.cmds.sent.last { it.type == "portico/answer" && !it.answered }.ok(obj("mode" to "relay"))
            r.cmds.last("portico/webrtc_offer").ok(obj("sdp" to "v=0 answer"))
            r.media.calls.last().answerCb!!(true)
            r.media.calls.last().listener.onConnected()
            sb.append(r.finish("device busy, then a retry that connects"))
        }
        run {
            val r = Rig(holdEnabled)
            r.mic = false
            r.ring(CALL)
            r.c.press(REF, CALL)
            r.c.onMicResult(false)
            sb.append(r.finish("microphone denied"))
        }
        run {
            val r = Rig(holdEnabled)
            r.ring(CALL)
            r.connect(CALL)
            r.c.appStopping()
            sb.append(r.finish("app stopping under a call"))
        }
        run {
            val r = Rig(holdEnabled)
            r.ring(CALL)
            r.connect(CALL)
            r.sched.advance(300_000)
            sb.append(r.finish("the five minute cap"))
        }
        run {
            val r = Rig(holdEnabled)
            r.ring(CALL)
            r.connect(CALL)
            r.c.hangUp()
            r.cmds.last("portico/hangup").lost() // owed
            r.ring("gso-next")
            r.c.press(REF, "gso-next")
            sb.append(r.finish("a hang-up owed, then the next call begins"))
        }
        run {
            val r = Rig(holdEnabled)
            r.active.save(ActiveCall("gate", "gso-left-behind", 7))
            r.c.onLinkReady()
            r.cmds.last("portico/hangup").ok()
            sb.append(r.finish("a restart releases the call the last run left"))
        }
        run {
            val r = Rig(holdEnabled)
            r.ring(CALL)
            r.connect(CALL)
            r.ring("gso-second")
            r.out += "canAnswerSecond=${r.c.canAnswer(REF, "gso-second")}"
            r.c.press(REF, "gso-second")
            sb.append(r.finish("a second ring while on a call is refused"))
        }
        return sb.toString()
    }

    @Test
    fun `the single-call flows equal the recorded pre-hold transcript - gate off and gate on`() {
        val off = flows(false)
        val on = flows(true)
        File("build").mkdirs()
        File("build/golden_single_call_out.txt").writeText(off)
        assertEquals(off, on, "the gate must not change a single-call flow")
        val golden = javaClass.getResourceAsStream("/golden_single_call_v1.txt")?.bufferedReader()?.readText()
        if (golden != null) assertEquals(golden, off, "must equal the transcript recorded from the pre-hold tree")
    }

    companion object {
        const val REF = "entry-gate"
        const val CALL = "gso-0123456789"
    }
}
