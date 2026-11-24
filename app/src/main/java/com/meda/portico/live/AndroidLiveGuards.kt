package com.meda.portico.live

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.PowerManager
import com.meda.portico.core.live.LiveGuards
import com.meda.portico.core.live.LiveLog
import com.meda.portico.core.live.PhoneGuards
import com.meda.portico.core.live.ThermalGuard
import com.meda.portico.core.live.ThermalSource

/**
 * The thin Android side of the live guards (DEV-713 S2); the policies are `:core`'s ([ThermalGuard], `DecoderCap`, [PhoneGuards]).
 *
 * - **Thermal.** `PowerManager.addThermalStatusListener` exists from API 29; below it (the app's minSdk is 26) there is nothing to listen
 *   to and the phone is never "hot" (the guard stays off, which is the 0.6.x behaviour). The listener is registered once, on the first
 *   planning pass of any live screen, and kept for the life of the process (a listener is a few bytes and the Journal wants the
 *   transitions); the current status is read when it is registered.
 * - **Decoder instances.** `MediaCodecInfo.CodecCapabilities.getMaxSupportedInstances()` (API 23) of the phone's H.264 decoder, read once on
 *   the live thread. Any failure reads as "did not say" (`:core` then assumes a conservative number).
 *
 * UNTESTED ON A PHONE (no emulator here): that the platform really reports these numbers is for the proof sitting.
 */
object AndroidLiveGuards {
    fun create(app: Context, log: LiveLog): LiveGuards =
        PhoneGuards(ThermalGuard(log), thermalSource(app), ::h264MaxInstances, log)

    /** Null below API 29: no thermal listener exists. */
    private fun thermalSource(app: Context): ThermalSource? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return ThermalSource { onStatus ->
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val listener = PowerManager.OnThermalStatusChangedListener { status -> onStatus(status) }
            pm.addThermalStatusListener(app.mainExecutor, listener)
            onStatus(pm.currentThermalStatus)
            return@ThermalSource { pm.removeThermalStatusListener(listener) }
        }
    }

    /** The H.264 decoder's `getMaxSupportedInstances()`, or null when the phone has none / does not say. May throw: [PhoneGuards] fences it. */
    private fun h264MaxInstances(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val probe = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(probe) ?: return null
        val info: MediaCodecInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { !it.isEncoder && it.name == name } ?: return null
        return info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).maxSupportedInstances
    }
}
