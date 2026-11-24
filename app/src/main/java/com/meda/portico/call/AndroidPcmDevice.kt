package com.meda.portico.call

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.meda.portico.core.call.AudioEnvWords
import com.meda.portico.core.call.AudioProfile
import com.meda.portico.core.call.DeviceReport
import com.meda.portico.core.call.PcmDevice

/**
 * The phone's microphone and speaker for away call audio, mono 16-bit in 40 ms frames at the [AudioProfile]'s rate: 16 kHz
 * (`l16`, the default) or 8 kHz (`pcmu`, Compatibility and the fallback). The microphone is `VOICE_COMMUNICATION` (the platform's echo
 * canceller and gain control where the device has them) and the speaker `USAGE_VOICE_COMMUNICATION`, paired with the
 * `MODE_IN_COMMUNICATION` route [AndroidAudioRoute] already sets; the platform resamples if the hardware runs at another rate.
 *
 * DEV-811 2.1: [prepare] creates both devices and attaches the effects WITHOUT starting them, so a device that cannot work at the
 * rate is known before the audio channel is opened (on a gate that open is the answer); [start] starts them and checks the recorder
 * is RECORDING and the player PLAYING; [stop] releases whatever exists, prepared or running.
 *
 * UNTESTED ON A PHONE: this host has no emulator. The framing, the codec and the queueing around it are
 * proven in `:core` against fakes. Two threads, both daemon: capture (blocking reads) and playout
 * (blocking writes, which pace themselves; silence is written when nothing is queued).
 */
class AndroidPcmDevice : PcmDevice {

    private val lock = Any()
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var captureThread: Thread? = null
    private var playoutThread: Thread? = null
    private var profile: AudioProfile? = null

    @Volatile private var aecWord = "unavailable"
    @Volatile private var nsWord = "unavailable"
    @Volatile private var readMax = 0L

    @Volatile
    private var running = false

    override fun prepare(profile: AudioProfile) {
        synchronized(lock) {
            check(record == null && track == null) { "already prepared" }
            try {
                val rate = profile.rate
                val frameBytes = profile.frameBytes
                val inMin = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val outMin = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(inMin > 0 && outMin > 0) { "audio parameters not supported" }
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(inMin, frameBytes * 4),
                )
                record = rec
                check(rec.state == AudioRecord.STATE_INITIALIZED) { "microphone not available" }
                val trk = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(outMin, frameBytes * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = trk
                check(trk.state == AudioTrack.STATE_INITIALIZED) { "speaker not available" }
                aecWord = "unavailable"
                nsWord = "unavailable"
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        aecWord = "failed"
                        aec = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true; aecWord = "on" }
                    }
                    if (NoiseSuppressor.isAvailable()) {
                        nsWord = "failed"
                        ns = NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true; nsWord = "on" }
                    }
                } catch (_: Exception) {
                    // Effects are a bonus; the call works without them (the journal says "failed").
                }
                this.profile = profile
            } catch (e: Exception) {
                releaseLocked()
                throw e
            }
        }
    }

    override fun start(onCapture: (ShortArray) -> Unit, nextPlayout: () -> ShortArray?, onError: (String) -> Unit) {
        synchronized(lock) {
            if (running) return
            val p = profile
            val rec = record
            val trk = track
            check(p != null && rec != null && trk != null) { "not prepared" }
            try {
                rec.startRecording()
                check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "recorder did not start" }
                trk.play()
                check(trk.playState == AudioTrack.PLAYSTATE_PLAYING) { "player did not start" }
            } catch (e: Exception) {
                releaseLocked()
                throw e
            }
            running = true
            captureThread = Thread({ captureLoop(rec, p, onCapture, onError) }, "portico-pcm-capture").apply { isDaemon = true; start() }
            playoutThread = Thread({ playoutLoop(trk, p, nextPlayout, onError) }, "portico-pcm-playout").apply { isDaemon = true; start() }
        }
    }

    private fun captureLoop(rec: AudioRecord, p: AudioProfile, onCapture: (ShortArray) -> Unit, onError: (String) -> Unit) {
        val n = p.frameSamples
        val buf = ShortArray(n)
        while (running) {
            var got = 0
            while (got < n && running) {
                val t0 = System.nanoTime()
                val r = rec.read(buf, got, n - got, AudioRecord.READ_BLOCKING)
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (ms > readMax) readMax = ms
                if (r < 0) {
                    if (running) onError("read")
                    return
                }
                got += r
            }
            if (got == n && running) {
                try {
                    onCapture(buf.copyOf())
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun playoutLoop(trk: AudioTrack, p: AudioProfile, nextPlayout: () -> ShortArray?, onError: (String) -> Unit) {
        val silence = ShortArray(p.frameSamples)
        while (running) {
            val frame = try {
                nextPlayout()
            } catch (_: Exception) {
                null
            } ?: silence
            if (trk.write(frame, 0, frame.size) < 0) {
                if (running) onError("write")
                return
            }
        }
    }

    /** Muting is done by the frame path (it sends silence); the device keeps reading so its buffers stay drained. */
    override fun setMuted(muted: Boolean) {}

    override fun report(): DeviceReport = DeviceReport(aecWord, nsWord, readMax)

    /**
     * 0.6.95: what the player and the recorder look like right now, as Journal fields: the speaker's usage (the one this class builds),
     * stream type, sample rate, buffer size and capacity in frames, performance mode and underrun count; the microphone's source, rate
     * and buffer. Read-only getters under [lock]; a released device gives nothing.
     */
    override fun facts(): List<Pair<String, Any?>> = synchronized(lock) {
        val out = ArrayList<Pair<String, Any?>>()
        val t = track
        if (t != null) {
            out += "usage" to "voicecomm"
            out += "strm" to AudioEnvWords.stream(try { t.streamType } catch (_: Throwable) { null })
            out += "outsr" to t.sampleRate
            out += "outbuf" to t.bufferSizeInFrames
            out += "outcap" to try { t.bufferCapacityInFrames } catch (_: Throwable) { null }
            out += "perf" to AudioEnvWords.perf(try { t.performanceMode } catch (_: Throwable) { null })
            out += "under" to try { t.underrunCount } catch (_: Throwable) { null }
        }
        val r = record
        if (r != null) {
            out += "insrc" to "voicecomm"
            out += "insr" to r.sampleRate
            out += "inbuf" to try { r.bufferSizeInFrames } catch (_: Throwable) { null }
        }
        out
    }

    override fun stop() {
        synchronized(lock) {
            running = false
            try { record?.stop() } catch (_: Exception) {}
            try { track?.stop() } catch (_: Exception) {}
            // Stopping unblocks the loops' read()/write(); wait for them to leave before release() frees the
            // native objects under them. Bounded, so a stuck driver cannot hang the caller; never join self.
            for (t in listOf(captureThread, playoutThread)) {
                if (t != null && t !== Thread.currentThread()) {
                    try { t.join(JOIN_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                }
            }
            captureThread = null
            playoutThread = null
            releaseLocked()
        }
    }

    /** Releases the effects and both devices, whether or not they ever started. Caller holds [lock]. */
    private fun releaseLocked() {
        try { aec?.release() } catch (_: Exception) {}
        try { ns?.release() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        record = null
        track = null
        aec = null
        ns = null
        profile = null
    }

    private companion object {
        const val JOIN_MS = 500L
    }
}
