package com.meda.portico.call

import android.content.Context
import com.meda.portico.core.call.AudioGate
import com.meda.portico.core.call.AudioGateSink
import com.meda.portico.core.call.AudioStats
import com.meda.portico.core.call.AudioStatsParser
import com.meda.portico.core.call.RawStat
import com.meda.portico.core.call.Cancellable
import com.meda.portico.core.call.RtcWindowSampler
import com.meda.portico.core.call.Scheduler
import com.meda.portico.core.call.MediaCall
import com.meda.portico.core.call.MediaListener
import com.meda.portico.core.call.MediaPort
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.push.PushJournal
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import com.meda.portico.core.call.NativeGuard
import com.meda.portico.core.call.OfferDelivery
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone's half of the browser-card exchange, on libwebrtc (`io.github.webrtc-sdk:android`, the
 * maintained build of Google's WebRTC): one send/receive audio transceiver with the microphone, a
 * non-trickle offer (every local candidate inside the SDP, as `internal/sfu` expects), no STUN and no
 * TURN (the design's tailnet/LAN-only world, §4.8).
 *
 * UNTESTED ON A PHONE. This host has no emulator, so nothing here has run: the protocol around it is
 * proven against [MediaCall]'s fake in `:core`. Every libwebrtc callback arrives on its own threads;
 * the owner (`SerialPorts`) re-posts them onto the answer path's executor.
 *
 * One factory per call, torn down on a separate thread: disposing a peer connection waits for
 * libwebrtc's signaling thread, which may be inside one of our callbacks.
 */
class WebRtcMedia(private val app: Context, private val log: HaLog, private val journal: PushJournal? = null) : MediaPort {

    override fun open(listener: MediaListener): MediaCall {
        initOnce(app)
        return Call(app, listener, log, journal)
    }

    private class Call(app: Context, private val listener: MediaListener, private val log: HaLog, private val journal: PushJournal?) : MediaCall {
        /** DEV-764: a number that grows by one per peer connection in this process, so lines of one call can be told from another's. */
        private val rtcId = nextId.incrementAndGet()
        private val bornMs = System.nanoTime() / 1_000_000
        @Volatile private var closedAtMs = -1L

        /** 0 = live, 1 = teardown running, 2 = native objects disposed. */
        @Volatile private var teardownState = 0

        /** An offer was asked for and its callback has not run yet. */
        @Volatile private var offerPending = false

        private fun ms(): Long = System.nanoTime() / 1_000_000 - bornMs

        /** DEV-764: one `ui rtc-*` line. Observation only (the journal catches); written BEFORE the native call it describes. */
        private fun jr(event: String, vararg fields: Pair<String, Any?>) {
            try {
                journal?.diag("ui", event, listOf<Pair<String, Any?>>("id" to rtcId, "ms" to ms()) + fields.toList())
            } catch (_: Throwable) {
            }
        }

        private val closed = AtomicBoolean(false)

        /** DEV-764 (app fixes): every touch of a native object goes through this; [teardown] closes it and waits before disposing. */
        private val guard = NativeGuard()

        /** The offer's gather-window timer, cancelled when the call closes. */
        @Volatile private var offerTimer: java.util.concurrent.ScheduledFuture<*>? = null
        private var adm: JavaAudioDeviceModule? = null
        private var factory: PeerConnectionFactory? = null
        private var source: AudioSource? = null
        private var track: AudioTrack? = null
        private var pc: PeerConnection? = null

        @Volatile
        private var gatheringDone: (() -> Unit)? = null

        /**
         * 0.6.95: libwebrtc's stats every 5 s while connected, as per-window deltas (`rtc-win`, `rtc-win-up`; [RtcWindowSampler]). Observation only:
         * the read goes through [guard] like every native touch, and the sampler stops before the teardown.
         */
        private val sampler = RtcWindowSampler(
            read = { done ->
                val p = pc
                if (p == null || closed.get()) done(null)
                else try {
                    if (!guard.run { p.getStats { report -> done(rawStats(report)) } }) done(null)
                } catch (_: Exception) {
                    done(null)
                }
            },
            emit = { event, fields ->
                try {
                    journal?.diag("ui", event, listOf<Pair<String, Any?>>("id" to rtcId, "ms" to ms()) + fields)
                } catch (_: Throwable) {
                }
            },
            scheduler = Scheduler { delayMs, task ->
                val f = timer.schedule({ try { task() } catch (_: Throwable) {} }, delayMs, TimeUnit.MILLISECONDS)
                Cancellable { f.cancel(false) }
            },
        )

        private val observer = object : PeerConnection.Observer {
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) gatheringDone?.invoke()
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                if (closed.get()) return
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        jr("rtc-state", "st" to "connected")
                        sampler.start()
                        listener.onConnected()
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> { jr("rtc-state", "st" to "disconnected"); listener.onDisconnected() }
                    PeerConnection.PeerConnectionState.FAILED -> { jr("rtc-state", "st" to "failed"); listener.onFailed() }
                    else -> Unit
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        }

        init {
            try {
                // 0.6.95: the builder is left at its defaults (no behaviour change); its settings are journalled and the device module's own
                // start/stop/error callbacks say when the speaker and the microphone really began. The error text is free text, so only its kind is kept.
                val hwAec = try { JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported() } catch (_: Throwable) { null }
                val hwNs = try { JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported() } catch (_: Throwable) { null }
                jr("rtc-adm-cfg", "builder" to "default", "hwaec" to hwAec, "hwns" to hwNs, "swaec" to "default", "swns" to "default", "swagc" to "default", "usage" to "default", "lowlat" to "default")
                val module = JavaAudioDeviceModule.builder(app)
                    .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                        override fun onWebRtcAudioTrackStart() = jr("rtc-adm", "ev" to "trkstart")
                        override fun onWebRtcAudioTrackStop() = jr("rtc-adm", "ev" to "trkstop")
                    })
                    .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                        override fun onWebRtcAudioRecordStart() = jr("rtc-adm", "ev" to "recstart")
                        override fun onWebRtcAudioRecordStop() = jr("rtc-adm", "ev" to "recstop")
                    })
                    .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                        override fun onWebRtcAudioTrackInitError(msg: String?) = jr("rtc-adm", "ev" to "trkerr", "kind" to "init")
                        override fun onWebRtcAudioTrackStartError(code: JavaAudioDeviceModule.AudioTrackStartErrorCode?, msg: String?) =
                            jr("rtc-adm", "ev" to "trkerr", "kind" to "start", "code" to code?.name?.lowercase()?.removePrefix("audio_track_start_"))
                        override fun onWebRtcAudioTrackError(msg: String?) = jr("rtc-adm", "ev" to "trkerr", "kind" to "run")
                    })
                    .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                        override fun onWebRtcAudioRecordInitError(msg: String?) = jr("rtc-adm", "ev" to "recerr", "kind" to "init")
                        override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode?, msg: String?) =
                            jr("rtc-adm", "ev" to "recerr", "kind" to "start", "code" to code?.name?.lowercase()?.removePrefix("audio_record_start_"))
                        override fun onWebRtcAudioRecordError(msg: String?) = jr("rtc-adm", "ev" to "recerr", "kind" to "run")
                    })
                    .createAudioDeviceModule()
                adm = module
                val f = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
                factory = f
                val s = f.createAudioSource(MediaConstraints())
                source = s
                val t = f.createAudioTrack("portico-mic", s)
                track = t
                val cfg = PeerConnection.RTCConfiguration(emptyList()).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
                }
                val p = f.createPeerConnection(cfg, observer) ?: throw IllegalStateException("no peer connection")
                pc = p
                p.addTrack(t, listOf("portico"))
                jr("rtc-new")
            } catch (e: Exception) {
                jr("rtc-new-failed", "ex" to e.javaClass.simpleName.lowercase())
                teardown()
                throw e
            }
        }

        override fun createOffer(gatherTimeoutMs: Long, onSdp: (String?) -> Unit) {
            val p = pc
            if (p == null || closed.get()) {
                jr("rtc-offer-skip", "closed" to closed.get(), "pc" to (p != null))
                onSdp(null)
                return
            }
            offerPending = true
            jr("rtc-offer-ask", "tmo" to gatherTimeoutMs)
            val delivery = OfferDelivery(
                guard,
                read = { p.localDescription?.description },
                onSdp = { sdp ->
                    offerPending = false
                    offerTimer?.cancel(false)
                    onSdp(sdp)
                },
                // DEV-764: written before p.localDescription is touched: a native crash there leaves this as its last word.
                before = { via, wasClosed ->
                    jr(
                        "rtc-offer-out", "via" to via, "closed" to wasClosed,
                        "teardown" to teardownState, "since" to (closedAtMs.takeIf { it >= 0 }?.let { ms() - it }),
                    )
                    if (via == "timeout") log.log(HaLog.Level.WARN, "answer: ICE gathering did not finish in $gatherTimeoutMs ms; sending what was gathered")
                },
            )
            gatheringDone = { delivery.deliver("gathered") }
            offerTimer = timer.schedule({ delivery.deliver("timeout") }, gatherTimeoutMs, TimeUnit.MILLISECONDS)
            if (closed.get()) offerTimer?.cancel(false) // closed while this ran: the timer would only find a closed call
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            }
            val started = guard.run {
                p.createOffer(
                    object : Observer() {
                        override fun onCreateSuccess(desc: SessionDescription) {
                            val ran = guard.run {
                                p.setLocalDescription(
                                    object : Observer() {
                                        override fun onSetSuccess() {
                                            var complete = false
                                            // Closed: deliver (it hands on null without a native read) so the session is never left waiting.
                                            val ran2 = guard.run { complete = p.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE }
                                            if (complete || !ran2) delivery.deliver("gathered")
                                        }

                                        override fun onSetFailure(error: String?) {
                                            jr("rtc-offer-fail", "via" to "setfail", "closed" to closed.get())
                                            delivery.fail()
                                        }
                                    },
                                    desc,
                                )
                            }
                            if (!ran) delivery.fail()
                        }

                        override fun onCreateFailure(error: String?) {
                            jr("rtc-offer-fail", "via" to "createfail", "closed" to closed.get())
                            delivery.fail()
                        }
                    },
                    constraints,
                )
            }
            if (!started) delivery.fail()
        }

        override fun setRemoteAnswer(sdp: String, onDone: (Boolean) -> Unit) {
            val p = pc
            if (p == null || closed.get()) {
                onDone(false)
                return
            }
            val ran = guard.run {
                p.setRemoteDescription(
                    object : Observer() {
                        override fun onSetSuccess() = onDone(true)
                        override fun onSetFailure(error: String?) = onDone(false)
                    },
                    SessionDescription(SessionDescription.Type.ANSWER, sdp),
                )
            }
            if (!ran) onDone(false)
        }

        /**
         * Mute and hold of THIS call, in the order [AudioGate] fixes. The three libwebrtc calls, all in
         * `io.github.webrtc-sdk:android:137.7151.05` (checked with javap on the pinned aar, 2026-10-22):
         * `org.webrtc.AudioTrack.setEnabled(boolean)`, `JavaAudioDeviceModule.setMicrophoneMute(boolean)` and
         * `JavaAudioDeviceModule.setSpeakerMute(boolean)`. Nothing is closed: the PeerConnection keeps sending RTP (silence while
         * the microphone is muted) and receiving it (discarded while the speaker is muted).
         */
        private val gate = AudioGate(object : AudioGateSink {
            override fun track(enabled: Boolean) {
                if (!guard.run { track?.setEnabled(enabled) ?: throw IllegalStateException("no audio track") }) throw IllegalStateException("closed")
            }

            override fun microphone(mute: Boolean) {
                val m = adm ?: throw IllegalStateException("no audio device")
                if (!guard.run { m.setMicrophoneMute(mute) }) throw IllegalStateException("closed")
            }

            override fun speaker(mute: Boolean) {
                val m = adm ?: throw IllegalStateException("no audio device")
                if (!guard.run { m.setSpeakerMute(mute) }) throw IllegalStateException("closed")
            }
        })

        override fun setMuted(muted: Boolean) {
            try {
                gate.setMuted(muted)
            } catch (e: Exception) {
                log.log(HaLog.Level.WARN, "answer: mute failed (${e.javaClass.simpleName})")
            }
        }

        /** HOLD / RESUME (15.13). Throws if the call is closed or libwebrtc refuses: the session then refuses the hold, nothing changed. */
        override fun setHeld(held: Boolean) {
            if (closed.get()) throw IllegalStateException("closed")
            gate.setHeld(held)
        }

        /**
         * DEV-698: ONE `getStats` for the journal's audio line (the app calls it once, about 2 s after the audio connects). The report
         * is reduced to [RawStat]s and read by the pure [AudioStatsParser]; null if the call is closed or libwebrtc refuses.
         */
        override fun audioStats(onStats: (AudioStats?) -> Unit) {
            val p = pc
            if (p == null || closed.get()) {
                onStats(null)
                return
            }
            try {
                if (!guard.run { p.getStats { report -> onStats(parse(report)) } }) onStats(null)
            } catch (e: Exception) {
                onStats(null)
            }
        }

        override fun close() = closeReporting {}

        /**
         * Close, reading the stats one last time first (DEV-698). The microphone track is switched off at once, on the caller's thread;
         * the read and the teardown happen on the teardown thread, the read bounded by [STATS_WAIT_MS], so a stuck `getStats` delays the
         * teardown by that much and no more. A repeat call does nothing (and reports nothing).
         */
        override fun closeReporting(onStats: (AudioStats?) -> Unit) {
            if (!closed.compareAndSet(false, true)) return
            closedAtMs = ms()
            jr("rtc-close", "pendingoffer" to offerPending)
            gatheringDone = null
            offerTimer?.cancel(false)
            sampler.stop()
            guard.close() // from here no callback or timer touches a native object
            try { track?.setEnabled(false) } catch (_: Exception) {}
            teardownExecutor.execute {
                var stats: AudioStats? = null
                val p = pc
                if (p != null) {
                    val done = CountDownLatch(1)
                    try {
                        p.getStats { report ->
                            stats = parse(report)
                            done.countDown()
                        }
                        done.await(STATS_WAIT_MS, TimeUnit.MILLISECONDS)
                    } catch (_: Exception) {
                    }
                }
                try { onStats(stats) } catch (_: Exception) {}
                teardown()
            }
        }

        private fun parse(report: RTCStatsReport): AudioStats? = try {
            AudioStatsParser.parse(report.statsMap.values.map { RawStat(it.type, it.id, it.members) })
        } catch (e: Exception) {
            null
        }

        /** The report as [RawStat]s for the per-window sampler; null if it cannot be read. */
        private fun rawStats(report: RTCStatsReport): List<RawStat>? = try {
            report.statsMap.values.map { RawStat(it.type, it.id, it.members) }
        } catch (e: Exception) {
            null
        }

        private fun teardown() {
            teardownState = 1
            jr("rtc-teardown", "pendingoffer" to offerPending)
            guard.close()
            // A touch already running (the timer's read, a libwebrtc callback) finishes first; bounded, never held across the dispose.
            val idle = guard.awaitIdle(IDLE_WAIT_MS)
            if (!idle) jr("rtc-teardown-busy")
            // Closes the microphone first; each step is independent and must not stop the next.
            try { track?.setEnabled(false) } catch (_: Exception) {}
            try { pc?.close() } catch (_: Exception) {}
            try { pc?.dispose() } catch (_: Exception) {}
            try { track?.dispose() } catch (_: Exception) {}
            try { source?.dispose() } catch (_: Exception) {}
            try { factory?.dispose() } catch (_: Exception) {}
            try { adm?.release() } catch (_: Exception) {}
            pc = null
            track = null
            source = null
            factory = null
            adm = null
            teardownState = 2
            jr("rtc-disposed", "pendingoffer" to offerPending)
        }
    }

    /** [SdpObserver] with the two callbacks each use does not need. */
    private abstract class Observer : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    private companion object {
        private val teardownExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "portico-rtc-teardown").apply { isDaemon = true } }
        private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "portico-rtc-timer").apply { isDaemon = true } }
        private val init = AtomicBoolean(false)
        private val nextId = AtomicInteger(0)

        /** How long a call's closing waits for its last stats read (DEV-698). */
        private const val STATS_WAIT_MS = 600L

        /** How long the teardown waits for a native touch already running before it disposes anyway. */
        private const val IDLE_WAIT_MS = 1500L

        fun initOnce(app: Context) {
            if (!init.compareAndSet(false, true)) return
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app.applicationContext).createInitializationOptions())
        }
    }
}
