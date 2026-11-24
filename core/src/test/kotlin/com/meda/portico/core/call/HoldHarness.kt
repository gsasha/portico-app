package com.meda.portico.core.call

import com.meda.portico.core.ha.Door
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.InMemoryDoorsCacheStore
import com.meda.portico.core.push.CallKey
import com.meda.portico.core.push.Decision
import com.meda.portico.core.push.InMemoryCallStore
import com.meda.portico.core.push.SocketEvents
import kotlinx.serialization.json.JsonPrimitive

/**
 * Two doors (the gate and the entrance), one phone, the real [AnswerCoordinator] over fakes, a direct executor and a fake
 * clock: the rig for every hold-and-switch test. [holdEnabled] is the gate under test.
 */
class HoldHarness(
    holdEnabled: Boolean,
    val config: AnswerConfig = AnswerConfig(),
    startMic: Boolean = true,
    journal: com.meda.portico.core.push.PushJournal? = null,
) {
    val cmds = FakeCommands()
    val media = FakeMedia()
    val realAudio = FakeAudio()
    val route = RefCountedAudioRoute(realAudio)
    val sched = FakeScheduler()
    val active = InMemoryActiveCallStore()
    val store = InMemoryCallStore()
    val doors = DoorsCache(
        InMemoryDoorsCacheStore(
            listOf(
                Door("gate-dev", GATE, driver = "gate_sip_observer", name = "Gate"),
                Door("ent-dev", ENT, driver = "dahua", name = "Entrance"),
            ),
        ),
    )
    var mic = startMic
    var paused = false
    var now = 1_000_000L
    val ringStops = mutableListOf<String>()
    var serviceStarts = 0
    var serviceStops = 0
    val order = mutableListOf<String>()

    val coordinator = AnswerCoordinator(
        newSession = { callId, doorId, hooks, onChange ->
            AnswerSession(callId, doorId, cmds, SerialMedia(media) { it() }, route, { mic }, sched, active, { now }, hooks, config, onChange = onChange)
        },
        callStore = store,
        doors = doors,
        sweep = AnswerSweep(cmds, active),
        post = { it() },
        stopRing = { door, call, why -> ringStops += "$door/$call/$why" },
        serviceStart = { serviceStarts++ },
        serviceStop = { serviceStops++ },
        paused = { paused },
        wallClockMs = { now },
        beforeBegin = { order += "before-begin" },
        holdEnabled = holdEnabled,
        journal = journal,
    )

    fun ring(ref: String, call: String) {
        Decision.receive(store, SocketEvents.ringing(ref, call, now), now, setOf(GATE, ENT))
    }

    /** The command of [type] that names [call]. */
    fun cmd(type: String, call: String): SentCommand =
        cmds.sent.last { it.type == type && (it.body["call_id"] as? JsonPrimitive)?.content == call }

    fun count(type: String, call: String): Int = cmds.sent.count { it.type == type && (it.body["call_id"] as? JsonPrimitive)?.content == call }

    /** Drives an already-pressed call from its offer to audio connected. */
    fun finishConnecting(call: String): FakeMediaCall {
        val m = media.calls.last()
        m.offerCb!!("v=0 offer")
        cmd("portico/answer", call).ok(obj("mode" to "relay"))
        cmd("portico/webrtc_offer", call).ok(obj("sdp" to "v=0 answer"))
        m.answerCb!!(true)
        m.listener.onConnected()
        return m
    }

    /** Rings, presses Answer, and connects. */
    fun connect(ref: String, call: String): FakeMediaCall {
        ring(ref, call)
        coordinator.press(ref, call)
        return finishConnecting(call)
    }

    /** Rings and presses "hold and answer", and connects. */
    fun connectOnHold(ref: String, call: String): FakeMediaCall {
        ring(ref, call)
        coordinator.holdAndAnswer(ref, call)
        return finishConnecting(call)
    }

    fun snapshotOf(call: String): AnswerSnapshot? = coordinator.sessionsState().sessions.lastOrNull { it.key.callId == call }?.snapshot

    fun key(ref: String, call: String) = CallKey(ref, call)

    /** Everything observable about the answer path, for the golden comparison. */
    fun transcript(): List<String> = buildList {
        add("service starts=$serviceStarts stops=$serviceStops")
        add("ring stops=$ringStops")
        add("commands=" + cmds.sent.map { it.type + ":" + it.body.toString() })
        add("media trace=" + media.trace)
        add("audio enters=${realAudio.enters} leaves=${realAudio.leaves}")
        add("store=" + active.loadAll())
        add("state=" + coordinator.state())
    }

    companion object {
        const val GATE = "gate-ref"
        const val ENT = "entrance-ref"
        const val A = "gso-aaaaaaaa"
        const val B = "gso-bbbbbbbb"
        const val C = "gso-cccccccc"
    }
}
