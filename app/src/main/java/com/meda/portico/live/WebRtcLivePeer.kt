package com.meda.portico.live

import com.meda.portico.core.ha.LiveCandidate
import com.meda.portico.core.live.LivePeer
import com.meda.portico.core.live.LivePeerListener
import com.meda.portico.core.live.RtcReading
import com.meda.portico.core.live.StatsPacer
import org.webrtc.RTCStatsReport
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.SurfaceViewRenderer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Where a stream's frames are drawn: the renderer of the tile that shows it now (swapped on a re-layout, never a renegotiation). */
class LiveSlot {
    @Volatile
    var renderer: SurfaceViewRenderer? = null
}

/**
 * The phone's half of a live tile (DEV-713 S1): ONE receive-only VIDEO transceiver in a peer connection of its own, a non-trickle
 * offer (every local candidate inside the SDP, gathered once, no STUN, no TURN: the house LAN), the server's answer and candidates
 * applied by the session. No audio line, no audio track, no microphone: the only transceiver is `MEDIA_TYPE_VIDEO` and
 * `RECV_ONLY`. A call's peer connection (the audio path) is never touched.
 *
 * UNTESTED ON A PHONE. Every libwebrtc callback arrives on its own threads and is handed to [post] (the live thread); a peer
 * connection is closed and disposed on [LiveVideoEngine.teardown], never on a libwebrtc or the live thread.
 */
class WebRtcLivePeer(
    engine: LiveVideoEngine,
    private val slot: LiveSlot,
    /** Runs a task on the live thread. */
    private val post: (() -> Unit) -> Unit,
    /** Runs a task on the live thread after a delay; returns the cancel action. */
    private val after: (Long, () -> Unit) -> () -> Unit,
    private val listener: LivePeerListener,
) : LivePeer {
    private val closed = AtomicBoolean(false)
    private val frames = AtomicLong()
    private val firstFrame = AtomicBoolean(false)
    private var track: VideoTrack? = null

    @Volatile
    private var gatheringDone: (() -> Unit)? = null

    /** Counts the frames (the fps label and the stall rule read the count) and hands each to the tile's renderer, whichever it is now. */
    private val sink = VideoSink { frame: VideoFrame ->
        frames.incrementAndGet()
        if (firstFrame.compareAndSet(false, true)) post { if (!closed.get()) listener.onFirstFrame() }
        slot.renderer?.onFrame(frame)
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gatheringDone?.invoke()
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            if (closed.get()) return
            post { if (!closed.get()) listener.onState("pc", state.name.lowercase()) }
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> post { if (!closed.get()) listener.onConnected() }
                PeerConnection.PeerConnectionState.DISCONNECTED -> post { if (!closed.get()) listener.onDisconnected() }
                PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED -> post { if (!closed.get()) listener.onFailed() }
                else -> Unit
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            attach(transceiver.receiver?.track())
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (!closed.get()) post { if (!closed.get()) listener.onState("ice", state.name.lowercase()) }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceCandidate(candidate: IceCandidate) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
    }

    private val pc: PeerConnection

    init {
        val f = engine.factory()
        val cfg = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        pc = f.createPeerConnection(cfg, observer) ?: throw IllegalStateException("no peer connection")
        try {
            val tr = pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )
            attach(tr.receiver?.track())
        } catch (e: Exception) {
            closeNow()
            throw e
        }
    }

    @Synchronized
    private fun attach(t: MediaStreamTrack?) {
        val v = t as? VideoTrack ?: return
        if (track === v) return
        track?.removeSink(sink)
        track = v
        v.addSink(sink)
    }

    override fun createOffer(gatherTimeoutMs: Long, onSdp: (String?) -> Unit) {
        if (closed.get()) return onSdp(null)
        val once = AtomicBoolean(false)
        fun deliver(fromTimeout: Boolean) {
            if (!once.compareAndSet(false, true)) return
            val sdp = try {
                pc.localDescription?.description
            } catch (_: Exception) {
                null
            }
            post { onSdp(sdp) }
        }
        gatheringDone = { deliver(false) }
        after(gatherTimeoutMs) { deliver(true) }
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        pc.createOffer(
            object : Observer() {
                override fun onCreateSuccess(desc: SessionDescription) {
                    pc.setLocalDescription(
                        object : Observer() {
                            override fun onSetSuccess() {
                                if (pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) deliver(false)
                            }

                            override fun onSetFailure(error: String?) {
                                if (once.compareAndSet(false, true)) post { onSdp(null) }
                            }
                        },
                        desc,
                    )
                }

                override fun onCreateFailure(error: String?) {
                    if (once.compareAndSet(false, true)) post { onSdp(null) }
                }
            },
            constraints,
        )
    }

    override fun setRemoteAnswer(sdp: String, onDone: (Boolean) -> Unit) {
        if (closed.get()) return onDone(false)
        pc.setRemoteDescription(
            object : Observer() {
                override fun onSetSuccess() = post { onDone(true) }
                override fun onSetFailure(error: String?) = post { onDone(false) }
            },
            SessionDescription(SessionDescription.Type.ANSWER, sdp),
        )
    }

    override fun addRemoteCandidate(candidate: LiveCandidate) {
        if (closed.get() || candidate.isEnd) return
        pc.addIceCandidate(IceCandidate(candidate.sdpMid ?: "0", candidate.sdpMLineIndex ?: 0, candidate.candidate))
    }

    override fun frameCount(): Long = frames.get()

    /** Serialises [stats] with the disposal in [closeNow], so a statistics call never reaches a disposed peer connection. */
    private val nativeLock = Any()

    /** DEV-764: one reading of the receiver's statistics; [onReading] runs on the live thread, null when none could be had. Observation only. */
    override fun stats(onReading: (RtcReading?) -> Unit) {
        try {
            synchronized(nativeLock) {
                if (closed.get()) {
                    onReading(null)
                    return
                }
                pc.getStats { report ->
                    val r = try {
                        reading(report)
                    } catch (_: Throwable) {
                        null
                    }
                    post { if (!closed.get()) onReading(r) }
                }
            }
        } catch (_: Throwable) {
            onReading(null)
        }
    }

    private fun reading(report: RTCStatsReport): RtcReading? {
        val all = report.statsMap
        val inbound = all.values.firstOrNull { it.type == "inbound-rtp" && ((it.members["kind"] ?: it.members["mediaType"]) == "video") } ?: return null
        val m = inbound.members
        fun num(key: String, from: Map<String, Any?> = m): Number? = from[key] as? Number
        val pairId = all.values.firstOrNull { it.type == "transport" }?.members?.get("selectedCandidatePairId") as? String
        val pair = (pairId?.let { all[it] } ?: all.values.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true })?.members
        val local = (pair?.get("localCandidateId") as? String)?.let { all[it] }?.members
        val remote = (pair?.get("remoteCandidateId") as? String)?.let { all[it] }?.members
        return RtcReading(
            fps = num("framesPerSecond")?.toDouble()?.let { Math.round(it).toInt() },
            decoded = num("framesDecoded")?.toLong(),
            dropped = num("framesDropped")?.toLong(),
            bytes = num("bytesReceived")?.toLong(),
            packets = num("packetsReceived")?.toLong(),
            lost = num("packetsLost")?.toLong(),
            jitterMs = num("jitter")?.toDouble()?.let { Math.round(it * 1000).toInt() },
            freezes = num("freezeCount")?.toLong(),
            freezeMs = num("totalFreezesDuration")?.toDouble()?.let { Math.round(it * 1000) },
            width = num("frameWidth")?.toInt(),
            height = num("frameHeight")?.toInt(),
            decoder = m["decoderImplementation"] as? String,
            rttMs = pair?.let { num("currentRoundTripTime", it) }?.toDouble()?.let { Math.round(it * 1000).toInt() },
            localType = local?.get("candidateType") as? String,
            remoteType = remote?.get("candidateType") as? String,
            net = local?.get("networkType") as? String,
            proto = local?.get("protocol") as? String,
        )
    }

    override fun close() {
        closeNow()
    }

    private fun closeNow() {
        if (!closed.compareAndSet(false, true)) return
        gatheringDone = null
        synchronized(this) {
            track?.removeSink(sink)
            track = null
        }
        LiveVideoEngine.teardown.execute {
            try {
                synchronized(nativeLock) {
                    pc.close()
                    pc.dispose()
                }
            } catch (_: Exception) {
                // already gone
            }
        }
    }

    /** [SdpObserver] with the two callbacks each use does not need. */
    private abstract class Observer : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
