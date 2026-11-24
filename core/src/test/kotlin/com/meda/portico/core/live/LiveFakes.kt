package com.meda.portico.core.live

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.LiveCandidate
import com.meda.portico.core.ha.LiveCommands

/** A clock that only moves when a test says so; tasks run in time order, a cancelled one never. */
class FakeLiveClock : LiveClock {
    var now = 0L
    private class Timer(val at: Long, val seq: Int, val task: () -> Unit) {
        var cancelled = false
    }

    private val queue = ArrayList<Timer>()
    private var seq = 0

    override fun nowMs() = now

    override fun after(delayMs: Long, task: () -> Unit): () -> Unit {
        val t = Timer(now + delayMs, seq++, task)
        queue += t
        return { t.cancelled = true }
    }

    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = queue.filter { !it.cancelled && it.at <= end }.minWithOrNull(compareBy({ it.at }, { it.seq })) ?: break
            queue.remove(next)
            now = next.at
            next.task()
        }
        now = end
    }

    fun pending(): Int = queue.count { !it.cancelled }
}

const val VIDEO_ONLY_SDP = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=recvonly\r\n"

class FakePeer(val clock: FakeLiveClock, val listener: LivePeerListener) : LivePeer {
    var offerSdp: String? = VIDEO_ONLY_SDP
    var holdOffer = false
    var heldOffer: ((String?) -> Unit)? = null
    var holdAnswer = false
    var heldAnswer: ((Boolean) -> Unit)? = null
    var answerOk = true
    var frames = 0L
    var closed = 0
    val answers = ArrayList<String>()
    val candidates = ArrayList<LiveCandidate>()

    override fun createOffer(gatherTimeoutMs: Long, onSdp: (String?) -> Unit) {
        if (holdOffer) heldOffer = onSdp else onSdp(offerSdp)
    }

    override fun setRemoteAnswer(sdp: String, onDone: (Boolean) -> Unit) {
        answers += sdp
        if (holdAnswer) heldAnswer = onDone else onDone(answerOk)
    }

    override fun addRemoteCandidate(candidate: LiveCandidate) {
        candidates += candidate
    }

    override fun frameCount() = frames

    override fun close() {
        closed++
    }
}

class FakeLiveWire : LiveWire {
    class Open(val camera: String, val size: CameraSize, val sdp: String, val sink: LiveWireSink) {
        var cancelled = false
    }

    val opens = ArrayList<Open>()
    val ices = ArrayList<Pair<String, LiveCandidate>>()
    val closes = ArrayList<String>()

    override fun open(cameraId: String, sdp: String, size: CameraSize, sink: LiveWireSink): LiveWireCall {
        val o = Open(cameraId, size, sdp, sink)
        opens += o
        return object : LiveWireCall {
            override fun cancel() {
                o.cancelled = true
            }
        }
    }

    override fun ice(sessionId: String, candidate: LiveCandidate) {
        ices += sessionId to candidate
    }

    override fun close(sessionId: String) {
        closes += sessionId
    }
}

class FakeLiveLog : LiveLog {
    val lines = ArrayList<String>()
    override fun started(key: LiveKey, stream: String?, ttffMs: Long) {
        lines += "start $key stream=$stream ttff=$ttffMs"
    }

    override fun failed(key: LiveKey, why: LiveWhy, retry: Int, willRetry: Boolean) {
        lines += "fail $key ${why.word} retry=$retry next=$willRetry"
    }

    override fun stopped(key: LiveKey, why: StopWhy, playedMs: Long) {
        lines += "stop $key ${why.word} played=$playedMs"
    }

    override fun thermal(status: Int, hot: Boolean) {
        lines += "thermal ${ThermalGuard.word(status)} hot=$hot"
    }

    override fun fault(at: String, error: String) {
        lines += "fault $at $error"
    }
}

/** One session under test with every port faked. */
class SessionRig(val key: LiveKey = LiveKey("gate", CameraSize.BIG), config: LiveSession.Config = LiveSession.Config()) {
    val clock = FakeLiveClock()
    val wire = FakeLiveWire()
    val log = FakeLiveLog()
    val peers = ArrayList<FakePeer>()
    var failCreate = false
    var changes = 0

    val session = LiveSession(
        key,
        { listener ->
            if (failCreate) throw IllegalStateException("no decoder")
            FakePeer(clock, listener).also { peers += it }
        },
        wire, clock, log, config, onChange = { changes++ },
    )

    val peer get() = peers.last()
    val open get() = wire.opens.last()

    /** The server accepts the offer: the result, then the `session` event. */
    fun accept(id: String = "live-1", stream: String = "main") {
        open.sink.onReply(LiveCommands.Reply.Opened(id, stream))
        open.sink.onEvent(LiveCommands.Event.Session(id, stream))
    }

    fun answer(sdp: String = "v=0 ANSWER") = open.sink.onEvent(LiveCommands.Event.Answer(sdp))

    fun candidate(n: Int = 1) = open.sink.onEvent(LiveCommands.Event.Candidate(LiveCandidate("candidate:$n 1 UDP 1 192.168.1.5 8555 typ host", "0")))

    /** Start, accept, answer, first frame: PLAYING. */
    fun play(id: String = "live-1") {
        session.start()
        accept(id)
        answer()
        peer.listener.onConnected()
        peer.listener.onFirstFrame()
    }
}
