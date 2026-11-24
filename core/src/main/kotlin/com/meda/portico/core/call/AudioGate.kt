package com.meda.portico.core.call

/**
 * The three switches libwebrtc gives one call's audio (design 15.13, "How hold works on the phone"), as the sequence logic of
 * HOLD and MUTE needs them. The real sink is `WebRtcMedia` in `:app`: [track] is `AudioTrack.setEnabled`, [microphone] is
 * `JavaAudioDeviceModule.setMicrophoneMute`, [speaker] is `JavaAudioDeviceModule.setSpeakerMute` (all three exist in
 * `io.github.webrtc-sdk:android:137.7151.05`; checked with javap against the pinned aar). Any may throw.
 */
interface AudioGateSink {
    /** Sends (true) or stops sending (false) the microphone track's samples. */
    fun track(enabled: Boolean)

    /** The audio device stops capturing into the call (true = off). The PeerConnection and its RTP stay up and send silence. */
    fun microphone(mute: Boolean)

    /** The audio device plays nothing of the call (true = off). */
    fun speaker(mute: Boolean)
}

/**
 * The order and the memory of mute and hold for ONE call, kept apart from libwebrtc so the JVM can test them:
 *
 * - **Hold** turns the track off, then the microphone off, then the speaker off; nothing is closed. **Resume** turns the speaker
 *   on FIRST, then the microphone, then the track - and the track comes back only if the person had not muted the call
 *   (`held` and `muted` are separate: 15.13 rule 3).
 * - A partly applied change is rolled back and the failure rethrown, so the session refuses the hold and the call is as it was
 *   (a hold that left the microphone muted but the speaker on would be worse than no hold).
 * - [setMuted] while held only remembers the mute; it is applied at resume.
 *
 * Not thread-safe: called on the answer thread, like the [MediaCall] it serves.
 */
class AudioGate(private val sink: AudioGateSink) {
    var muted = false
        private set
    var held = false
        private set

    fun setMuted(muted: Boolean) {
        this.muted = muted
        if (!held) sink.track(!muted)
    }

    fun setHeld(held: Boolean) {
        if (held == this.held) return
        if (held) {
            try {
                sink.track(false)
                sink.microphone(true)
                sink.speaker(true)
            } catch (e: Exception) {
                // Put back what the failed hold may have touched; each step is independent.
                quietly { sink.speaker(false) }
                quietly { sink.microphone(false) }
                quietly { sink.track(!muted) }
                throw e
            }
            this.held = true
        } else {
            try {
                sink.speaker(false)
                sink.microphone(false)
                sink.track(!muted)
            } catch (e: Exception) {
                // The call must not be left half open: back to held (silent), the person presses again.
                quietly { sink.track(false) }
                quietly { sink.microphone(true) }
                quietly { sink.speaker(true) }
                throw e
            }
            this.held = false
        }
    }

    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }
}

/** What an Android audio-focus change means for the answer path (H2). The constants are `AudioManager.AUDIOFOCUS_*`. */
object AudioFocusPolicy {
    const val GAIN = 1
    const val GAIN_TRANSIENT = 2
    const val GAIN_TRANSIENT_MAY_DUCK = 3
    const val GAIN_TRANSIENT_EXCLUSIVE = 4
    const val LOSS = -1
    const val LOSS_TRANSIENT = -2
    const val LOSS_TRANSIENT_CAN_DUCK = -3

    enum class Event {
        /** Another app has the audio: silence the live call and say so. */
        LOST,

        /** The audio is ours again: put the live call back as it was. */
        REGAINED,

        /** Ducking and anything unknown: the call is left alone (the system lowers the volume itself). */
        IGNORE,
    }

    fun interpret(change: Int): Event = when (change) {
        LOSS, LOSS_TRANSIENT -> Event.LOST
        GAIN, GAIN_TRANSIENT, GAIN_TRANSIENT_MAY_DUCK, GAIN_TRANSIENT_EXCLUSIVE -> Event.REGAINED
        else -> Event.IGNORE
    }
}
