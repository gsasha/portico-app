package com.meda.portico.core.call

import com.meda.portico.core.ha.HaError
import com.meda.portico.core.ha.HaInbound
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A command sent through the port, waiting for the test to answer it. */
class SentCommand(val type: String, val body: JsonObject, val reply: (CommandReply) -> Unit) {
    var answered = false
    fun ok(result: JsonElement? = null) = answer(CommandReply.Reply(HaInbound.Result(1, true, result, null)))
    fun error(code: String, message: String = "") = answer(CommandReply.Reply(HaInbound.Result(1, false, null, HaError(code, message))))
    fun lost() = answer(CommandReply.Lost)
    private fun answer(r: CommandReply) {
        check(!answered) { "$type answered twice" }
        answered = true
        reply(r)
    }
}

class FakeCommands : CommandPort {
    val sent = mutableListOf<SentCommand>()
    var connected = true
    override fun send(type: String, body: JsonObject, onReply: (CommandReply) -> Unit) {
        if (!connected) {
            onReply(CommandReply.NotSent)
            return
        }
        sent += SentCommand(type, body, onReply)
    }

    fun types() = sent.map { it.type }
    fun last(type: String) = sent.last { it.type == type }
    fun count(type: String) = sent.count { it.type == type }
}

class FakeMediaCall(val listener: MediaListener, val name: String = "call", private val trace: MutableList<String>? = null) : MediaCall {
    var offerCb: ((String?) -> Unit)? = null
    var gatherTimeout = -1L
    var answerSdp: String? = null
    var answerCb: ((Boolean) -> Unit)? = null
    var closes = 0
    var micOff = false

    /** Hold (15.13): the speaker and the microphone are both off, no leg is closed. */
    var heldNow = false
    val holds = mutableListOf<Boolean>()
    var failHold = false

    /** A media that forgets the person's mute on resume (the session must put it back). */
    var resumeForgetsMute = false
    val micOpen: Boolean get() = !heldNow && !micOff
    val speakerOn: Boolean get() = !heldNow
    override fun createOffer(gatherTimeoutMs: Long, onSdp: (String?) -> Unit) {
        gatherTimeout = gatherTimeoutMs
        offerCb = onSdp
    }

    override fun setRemoteAnswer(sdp: String, onDone: (Boolean) -> Unit) {
        answerSdp = sdp
        answerCb = onDone
    }

    override fun setMuted(muted: Boolean) {
        micOff = muted
        trace?.add("$name mute=$muted")
    }

    override fun setHeld(held: Boolean) {
        if (failHold) throw IllegalStateException("cannot hold")
        holds += held
        this.heldNow = held
        if (!held && resumeForgetsMute) micOff = false
        trace?.add("$name held=$held")
    }

    override fun close() {
        closes++
        trace?.add("$name close")
    }
}

class FakeMedia : MediaPort {
    val calls = mutableListOf<FakeMediaCall>()
    val trace = mutableListOf<String>()
    var failOpen = false
    override fun open(listener: MediaListener): MediaCall {
        if (failOpen) throw IllegalStateException("no audio")
        return FakeMediaCall(listener, "c${calls.size + 1}", trace).also { calls += it; trace.add("${it.name} open") }
    }
}

class FakeAudio : AudioRoute {
    var enters = 0
    var leaves = 0
    override fun enter() {
        enters++
    }

    override fun leave() {
        leaves++
    }
}

/** Virtual time: [advance] runs every due task in order. */
class FakeScheduler : Scheduler {
    var now = 0L
        private set
    private class Task(val at: Long, val run: () -> Unit) {
        var cancelled = false
    }

    private val tasks = mutableListOf<Task>()
    val pending: Int get() = tasks.count { !it.cancelled }

    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val t = Task(now + delayMs, task)
        tasks += t
        return Cancellable { t.cancelled = true }
    }

    fun advance(ms: Long) {
        val target = now + ms
        while (true) {
            val next = tasks.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
            now = next.at
            tasks.remove(next)
            next.run()
        }
        now = target
    }
}

fun obj(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { for ((k, v) in pairs) put(k, v) }
fun str(s: String): JsonElement = JsonPrimitive(s)

class FakeFramePath(val listener: MediaListener) : FramePath {
    var started: Pair<String, String>? = null
    var onResult: ((AnswerFailure?) -> Unit)? = null
    var closes = 0
    var mutedNow: Boolean? = null
    var heldNow = false
    val holds = mutableListOf<Boolean>()
    var failHold = false
    override fun setHeld(held: Boolean) {
        if (failHold) throw IllegalStateException("cannot hold")
        holds += held
        this.heldNow = held
    }

    override fun start(callId: String, doorId: String, onResult: (AnswerFailure?) -> Unit) {
        started = callId to doorId
        this.onResult = onResult
    }

    override fun setMuted(muted: Boolean) {
        this.mutedNow = muted
    }

    override fun close() {
        closes++
    }
}

class FakeAway(var prefer: Boolean = false) : AwayMedia {
    val paths = mutableListOf<FakeFramePath>()
    var failOpen = false
    override fun preferFrames(): Boolean = prefer
    override fun pathWhy(): String = if (prefer) "fake-frames" else "fake-webrtc"
    override fun open(listener: MediaListener): FramePath {
        if (failOpen) throw IllegalStateException("no audio")
        return FakeFramePath(listener).also { paths += it }
    }
}
