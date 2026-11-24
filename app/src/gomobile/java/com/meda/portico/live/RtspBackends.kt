package com.meda.portico.live

import com.meda.portico.core.live.RtspListener
import com.meda.portico.core.live.RtspSource
import com.meda.portico.core.live.RtspSourceFactory
import com.meda.portico.gomobile.tailnet.Tailnet
import com.meda.portico.gomobile.tailnet.VideoSink
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Compiled ONLY when `app/libs/gocore.aar` is present (app/build.gradle.kts, `gocore/bind.sh`). The other copy, under
 * `src/nogomobile/`, returns null (no RTSP client in this APK).
 *
 * The Go client (`gocore/tailnet/rtspvideo.go`, `Tailnet.startRtspVideo`) dials go2rtc through the tailnet node and hands H.264 NAL
 * units to a [VideoSink] on its own goroutine. The data callbacks go straight to the session's listener (which queues them for the
 * decoder, never blocking); the one control event, that the connection ended, is posted to the live thread.
 *
 * UNTESTED against the generated classes here (the bind needs Go and the NDK, which this host has not): the shapes used are gomobile's
 * documented ones (an exported Go interface becomes a Java interface with lower-camel methods, Go `int` and `int64` become `long`).
 */
object RtspBackends {
    fun create(post: (() -> Unit) -> Unit): RtspSourceFactory = RtspSourceFactory { url, listener -> GoRtspSource.open(url, listener, post) }
}

private object GoRtspSource {
    fun open(url: String, listener: RtspListener, post: (() -> Unit) -> Unit): RtspSource {
        val stopped = AtomicBoolean(false)
        val sink = object : VideoSink {
            override fun onFormat(sps: ByteArray, pps: ByteArray, width: Long, height: Long) {
                if (!stopped.get()) listener.onFormat(sps, pps, width.toInt(), height.toInt())
            }

            override fun onNal(nal: ByteArray, ptsUs: Long, key: Boolean) {
                if (!stopped.get()) listener.onNal(nal, ptsUs, key)
            }

            override fun onState(state: String) {
                // "connecting", "live", or "ended:<kind>" once, when the connection ends on its own (never after Stop)
                if (state.startsWith(ENDED)) post { if (!stopped.get()) listener.onEnded(state.removePrefix(ENDED)) }
            }
        }
        val handle = Tailnet.startRtspVideo(url, sink) // returns at once; connects on its own goroutine
        return object : RtspSource {
            override fun stop() {
                if (stopped.compareAndSet(false, true)) handle.stop()
            }
        }
    }

    private const val ENDED = "ended:"
}
