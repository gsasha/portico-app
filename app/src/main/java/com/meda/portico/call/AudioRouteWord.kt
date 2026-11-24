package com.meda.portico.call

import android.content.Context
import android.media.AudioManager

/** DEV-811 P1: where the call's sound is coming out right now, as one journal word (diagnostic only; never throws). */
object AudioRouteWord {
    @Suppress("DEPRECATION")
    fun now(context: Context): String = try {
        val am = context.getSystemService(AudioManager::class.java)
        when {
            am == null -> "unknown"
            am.isBluetoothScoOn -> "bt"
            am.isWiredHeadsetOn -> "wired"
            am.isSpeakerphoneOn -> "speaker"
            else -> "ear"
        }
    } catch (_: Exception) {
        "unknown"
    }
}
