package com.meda.portico.store

import android.content.SharedPreferences
import com.meda.portico.core.live.LiveStore

/**
 * The live/still choices this phone made with the LIVE chip (DEV-713), on their own private prefs file: one JSON string
 * ([com.meda.portico.core.live.LiveCodec]) of camera ids and two booleans, nothing secret. Written with `commit()`, so a tap is on
 * disk before the app can be killed. Only the [SharedPreferences] interface is used, so the JVM tests use a fake.
 */
class PrefsLiveStore(private val prefs: SharedPreferences, private val key: String = KEY) : LiveStore {
    override fun load(): String? = prefs.getString(key, null)

    override fun save(text: String): Boolean = prefs.edit().putString(key, text).commit()

    companion object {
        const val FILE = "portico_live"
        const val KEY = "live_v1"

        /** 0.6.53: the gear menu's per-camera last resolution and off switch ([com.meda.portico.core.content.CameraChoices]), in the same file. */
        const val CAMERA_KEY = "camera_v1"
    }
}
