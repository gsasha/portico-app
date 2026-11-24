package com.meda.portico.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.meda.portico.core.call.AudioRoute

/**
 * `MODE_IN_COMMUNICATION` for the length of a call (memory `call-audio-needs-in-communication-mode`),
 * the route chosen by [CallAudioControl] (speaker unless the phone is at the ear or a headset is connected), audio focus held, and everything put
 * back on [leave]. [leave] is idempotent and restores only what [enter] changed.
 */
class AndroidAudioRoute(
    context: Context,
    /**
     * M7 H2: told every audio-focus change of the request this route holds (`AudioManager.AUDIOFOCUS_*`), on the main thread.
     * The default does nothing, which is what the empty listener did before; only the answer path's route is given a real one
     * (it forwards to `AnswerCoordinator.onAudioFocusChange`, which acts only when hold is enabled).
     */
    private val onFocusChange: (Int) -> Unit = {},
) : AudioRoute {
    private val am = context.getSystemService(AudioManager::class.java)
    private val control = CallAudioControl.get(context)
    private var entered = false
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedSpeaker = false
    private var focus: AudioFocusRequest? = null

    @Synchronized
    override fun enter() {
        if (entered) return
        val m = am ?: return
        savedMode = m.mode
        @Suppress("DEPRECATION")
        savedSpeaker = m.isSpeakerphoneOn
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                try {
                    onFocusChange(change)
                } catch (_: Exception) {
                    // A focus callback must never take the app down; the call goes on as it was.
                }
            }
            .build()
        m.requestAudioFocus(req)
        focus = req
        m.mode = AudioManager.MODE_IN_COMMUNICATION
        entered = true
        // A-06: the loudspeaker is no longer forced; the route follows the person's key, the proximity sensor and any headset.
        control.callStarted()
    }

    @Synchronized
    override fun leave() {
        if (!entered) return
        entered = false
        control.callEnded()
        val m = am ?: return
        @Suppress("DEPRECATION")
        m.isSpeakerphoneOn = savedSpeaker
        m.mode = savedMode
        focus?.let { m.abandonAudioFocusRequest(it) }
        focus = null
    }
}
