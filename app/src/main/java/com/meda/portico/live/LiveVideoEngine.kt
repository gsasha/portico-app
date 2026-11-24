package com.meda.portico.live

import android.content.Context
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The libwebrtc pieces every live tile of one Home visit shares (DEV-713 S1): one EGL context (the decoders and the renderers use
 * it) and ONE video-receive-only [PeerConnectionFactory] with a hardware-preferring decoder factory and no encoder at all. It is
 * built on first use and [release]d when Home stops; the next visit makes a new one.
 *
 * VIDEO ONLY. Nothing here makes an audio source or track, and the microphone is never opened: the offer has no audio line, so no
 * audio send or receive stream ever exists. The factory still needs an audio device module (without one libwebrtc would try to make
 * its own native one, which this build is not set up for), so it gets the same [JavaAudioDeviceModule] the call path uses
 * (the audio path's factory), muted; building it opens nothing. A call has its own factory; the two share nothing.
 *
 * UNTESTED ON A PHONE (no emulator here): that this factory leaves the audio mode alone is checked by the source guard, not by a run.
 */
class LiveVideoEngine(private val app: Context) {
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var released = false

    /** The shared EGL context, or null when EGL cannot start or the engine was released (the tile then stays on its still). */
    @Synchronized
    fun eglContext(): EglBase.Context? = try {
        if (released) null else eglLocked().eglBaseContext
    } catch (_: Exception) {
        null
    }

    private fun eglLocked(): EglBase = egl ?: EglBase.create().also { egl = it }

    /** The factory; throws when it cannot be built (the session then fails with the decoder notice). */
    @Synchronized
    fun factory(): PeerConnectionFactory {
        check(!released) { "released" }
        factory?.let { return it }
        initOnce(app)
        val ctx = eglLocked().eglBaseContext
        val module = JavaAudioDeviceModule.builder(app).createAudioDeviceModule()
        module.setMicrophoneMute(true)
        adm = module
        return try {
            PeerConnectionFactory.builder()
                .setAudioDeviceModule(module)
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(ctx))
                .createPeerConnectionFactory()
                .also { factory = it }
        } catch (e: Exception) {
            module.release()
            adm = null
            throw e
        }
    }

    /** Disposes the factory and the EGL context after every peer connection already queued for disposal (same thread, in order). */
    fun release() {
        val f: PeerConnectionFactory?
        val m: JavaAudioDeviceModule?
        val e: EglBase?
        synchronized(this) {
            if (released) return
            released = true
            f = factory
            m = adm
            e = egl
            factory = null
            adm = null
            egl = null
        }
        teardown.execute {
            try {
                f?.dispose()
            } catch (_: Exception) {
                // already gone
            }
            try {
                m?.release()
            } catch (_: Exception) {
                // already gone
            }
            try {
                e?.release()
            } catch (_: Exception) {
                // already gone
            }
        }
    }

    companion object {
        /**
         * Disposing a peer connection or a factory waits for libwebrtc's signaling thread, which may be inside one of our callbacks:
         * so it is never done on the live thread. One thread, in order: every peer is disposed before its factory.
         */
        val teardown = Executors.newSingleThreadExecutor { r -> Thread(r, "portico-live-teardown").apply { isDaemon = true } }

        private val init = AtomicBoolean(false)

        fun initOnce(app: Context) {
            if (!init.compareAndSet(false, true)) return
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app.applicationContext).createInitializationOptions())
        }
    }
}
