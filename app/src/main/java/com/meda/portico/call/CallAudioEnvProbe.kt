package com.meda.portico.call

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import com.meda.portico.core.call.AudioEnvWords

/**
 * 0.6.95 (D-20261121-ee09): the Android audio environment of a call as Journal fields, read at the start and the end of its audio on BOTH
 * paths (`audio-env at=start|end`). Read-only: it asks the system and changes nothing. Each read is fenced, so a value the device
 * will not give is left out (never a made-up 0). Words come from [AudioEnvWords]; everything else is a small integer.
 *
 * `mode` the AudioManager mode; `vcall`/`vcallmax` the voice-call stream's volume index and its top (the stream the call audio uses);
 * `music`/`musicmax` the media stream; `spk` the loudspeaker flag; `comm` the communication device (API 31+); `mmute` microphone muted;
 * `playing` how many streams are playing and `pbusage` their usages (the app's own call track among them); `outsr`/`outfpb` the device's
 * native output rate and frames per buffer; `lowlat`/`pro` the low-latency and pro-audio features.
 */
object CallAudioEnvProbe {
    @Suppress("DEPRECATION")
    fun read(app: Context): List<Pair<String, Any?>> {
        val am = try { app.getSystemService(AudioManager::class.java) } catch (_: Throwable) { null } ?: return emptyList()
        fun <T> safe(f: () -> T): T? = try { f() } catch (_: Throwable) { null }
        val usages = safe { am.activePlaybackConfigurations.mapNotNull { safe { it.audioAttributes.usage } } }
        return listOf(
            "mode" to AudioEnvWords.mode(safe { am.mode }),
            "vcall" to safe { am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) },
            "vcallmax" to safe { am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) },
            "music" to safe { am.getStreamVolume(AudioManager.STREAM_MUSIC) },
            "musicmax" to safe { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) },
            "spk" to safe { am.isSpeakerphoneOn },
            "comm" to if (Build.VERSION.SDK_INT >= 31) AudioEnvWords.device(safe { am.communicationDevice?.type }) else null,
            "mmute" to safe { am.isMicrophoneMute },
            "playing" to usages?.size,
            "pbusage" to usages?.let { AudioEnvWords.usages(it) },
            "outsr" to safe { am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toInt() },
            "outfpb" to safe { am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toInt() },
            "lowlat" to safe { app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY) },
            "pro" to safe { app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO) },
        )
    }
}
