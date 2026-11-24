package com.meda.portico.live

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.meda.portico.core.live.NalQueue
import com.meda.portico.core.live.RtspDecoder
import com.meda.portico.core.live.RtspDecoderListener
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The H.264 decoder of one RTSP tile (DEV-713 S1b): the phone's hardware `MediaCodec` into the tile's plain `SurfaceView` (the old
 * intercom app's way, since 0.30.1-alpha). The Go client's thread only queues ([NalQueue], bounded; it resyncs on an IDR when the
 * decoder lags); ONE decoder thread of its own configures the codec from the parameter sets, starts it at an IDR, feeds it and draws
 * every output frame. Nothing here touches the live thread, the microphone or the audio mode.
 *
 * - Created by [create], which only checks that the phone HAS an H.264 decoder (a phone without one throws, and the tile stays on its
 *   still with the decoder notice). The codec itself is made on the decoder thread at the first IDR once a surface exists; a codec that
 *   cannot be made or that breaks reports [RtspDecoderListener.onFailed] (the session retries 2 s / 5 s / 15 s, then the notice).
 * - A surface that goes away (the tile was rebuilt) stops the codec; the next IDR, with the new surface, starts it again.
 * - The decoder is released by the session (stop or failure): the thread ends and releases the codec.
 *
 * UNTESTED ON A PHONE (no emulator here): decoder allocation, the first IDR, the surface swap and the thermal behaviour are for the
 * proof sitting. The queue ([NalQueue]) and the session are tested on the JVM.
 */
class MediaCodecRtspDecoder private constructor(
    private val slot: RtspSlot,
    private val post: (() -> Unit) -> Unit,
    private val listener: RtspDecoderListener,
) : RtspDecoder {
    private val queue = NalQueue()
    private val frames = AtomicLong()
    private val released = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val thread = Thread({ run() }, "portico-rtsp-decoder").apply { isDaemon = true }

    override fun format(sps: ByteArray, pps: ByteArray, width: Int, height: Int) = queue.offerFormat(sps, pps, width, height)

    override fun nal(nal: ByteArray, ptsUs: Long, key: Boolean) {
        queue.offerNal(nal, ptsUs, key)
    }

    override fun frameCount(): Long = frames.get()

    override fun release() {
        if (released.compareAndSet(false, true)) queue.close() // the thread sees it within one wait and releases the codec itself
    }

    private fun start() {
        thread.start()
    }

    // ---- the decoder thread --------------------------------------------------------------------

    private class Running(val codec: MediaCodec, val surface: Surface)

    private fun run() {
        var running: Running? = null
        var format: NalQueue.Item.Format? = null
        var needKey = true
        var firstOut = true
        try {
            while (!released.get()) {
                val item = queue.take(WAIT_MS)
                if (item is NalQueue.Item.Format) {
                    if (format == null || !same(format, item)) {
                        stopCodec(running)
                        running = null
                        needKey = true
                    }
                    format = item
                } else if (item is NalQueue.Item.Nal) {
                    var r = running
                    if (r != null && slot.surface() !== r.surface) { // the tile's view went away: wait for the next one and an IDR
                        stopCodec(r)
                        running = null
                        r = null
                        needKey = true
                    }
                    if (needKey) {
                        if (!item.key || format == null) continue // nothing is fed before an IDR (and parameter sets)
                    }
                    if (r == null) {
                        val surface = slot.surface() ?: continue // no view yet: this IDR is lost, the next one will do
                        r = Running(startCodec(format!!, surface), surface)
                        running = r
                        firstOut = true
                    }
                    needKey = false
                    if (!feed(r.codec, item)) {
                        // the codec would not take it in 100 ms: it is behind. Start again from the next IDR rather than tear the picture.
                        r.codec.flush()
                        needKey = true
                    }
                }
                val r = running
                if (r != null) {
                    if (drain(r, firstOut)) firstOut = false
                }
            }
        } catch (e: Throwable) {
            if (!released.get()) post { listener.onFailed() }
        } finally {
            stopCodec(running)
        }
    }

    private fun same(a: NalQueue.Item.Format, b: NalQueue.Item.Format) =
        a.width == b.width && a.height == b.height && a.sps.contentEquals(b.sps) && a.pps.contentEquals(b.pps)

    private fun startCodec(f: NalQueue.Item.Format, surface: Surface): MediaCodec {
        val w = if (f.width in 16..8192) f.width else 1280
        val h = if (f.height in 16..8192) f.height else 720
        val mf = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(f.sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(f.pps))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, NalQueue.MAX_NAL_BYTES)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(mf) ?: throw IllegalStateException("no H.264 decoder")
        val codec = MediaCodec.createByCodecName(name)
        try {
            codec.setOnFrameRenderedListener({ _, _, _ -> slot.rendered() }, main)
            codec.configure(mf, surface, null, 0)
            codec.start()
        } catch (e: Throwable) {
            try {
                codec.release()
            } catch (_: Exception) {
                // never started
            }
            throw e
        }
        slot.videoSize(w, h)
        return codec
    }

    /** Hands one NAL unit (start code included) to the codec; false when no input buffer came free in 100 ms. */
    private fun feed(codec: MediaCodec, nal: NalQueue.Item.Nal): Boolean {
        var tries = 0
        while (tries < 10 && !released.get()) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) {
                val buf = codec.getInputBuffer(i) ?: return false
                buf.clear()
                if (nal.bytes.size > buf.capacity()) {
                    codec.queueInputBuffer(i, 0, 0, nal.ptsUs, 0) // never overruns a buffer: hand it back empty and resync
                    return false
                }
                buf.put(nal.bytes)
                codec.queueInputBuffer(i, 0, nal.bytes.size, nal.ptsUs, 0)
                return true
            }
            tries++
        }
        return false
    }

    /** Draws every finished frame; true when at least one was drawn (the first calls [RtspDecoderListener.onFirstFrame]). */
    private fun drain(r: Running, first: Boolean): Boolean {
        val info = MediaCodec.BufferInfo()
        var any = false
        while (!released.get()) {
            val i = r.codec.dequeueOutputBuffer(info, 0)
            when {
                i >= 0 -> {
                    // Under the slot's lock: after surfaceDestroyed returns nothing may draw on the surface.
                    synchronized(slot.lock) { r.codec.releaseOutputBuffer(i, slot.surface() === r.surface) }
                    frames.incrementAndGet()
                    if (first && !any) post { listener.onFirstFrame() }
                    any = true
                }
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val of = r.codec.outputFormat
                    if (of.containsKey(MediaFormat.KEY_WIDTH) && of.containsKey(MediaFormat.KEY_HEIGHT)) {
                        // the displayed picture is the crop rectangle when the codec reports one (the buffer may be padded)
                        val cropped = of.containsKey("crop-left") && of.containsKey("crop-right") && of.containsKey("crop-top") && of.containsKey("crop-bottom")
                        val w = if (cropped) of.getInteger("crop-right") - of.getInteger("crop-left") + 1 else of.getInteger(MediaFormat.KEY_WIDTH)
                        val h = if (cropped) of.getInteger("crop-bottom") - of.getInteger("crop-top") + 1 else of.getInteger(MediaFormat.KEY_HEIGHT)
                        if (w > 0 && h > 0) slot.videoSize(w, h)
                    }
                }
                else -> return any
            }
        }
        return any
    }

    private fun stopCodec(r: Running?) {
        if (r == null) return
        try {
            r.codec.stop()
        } catch (_: Exception) {
            // not started or already stopped
        }
        try {
            r.codec.release()
        } catch (_: Exception) {
            // already released
        }
    }

    companion object {
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val WAIT_MS = 20L

        /** Throws when the phone has no H.264 decoder. Starts the decoder thread. */
        fun create(slot: RtspSlot, post: (() -> Unit) -> Unit, listener: RtspDecoderListener): MediaCodecRtspDecoder {
            val probe = MediaFormat.createVideoFormat(MIME, 1280, 720)
            check(MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(probe) != null) { "no H.264 decoder" }
            return MediaCodecRtspDecoder(slot, post, listener).also { it.start() }
        }
    }
}
