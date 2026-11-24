package com.meda.portico.store

import android.content.SharedPreferences
import com.meda.portico.core.content.SizeStore

/**
 * The camera sizes this phone chose (blocks B6), on their own private prefs file: one JSON string ([com.meda.portico.core.content.SizeCodec])
 * of page and camera ids and two sizes, nothing secret. Written with `commit()`, so a tap is on disk before the app can be
 * killed; it is a few dozen bytes on a tap. Only the [SharedPreferences] interface is used, so the JVM tests use a fake.
 */
class PrefsSizeStore(private val prefs: SharedPreferences) : SizeStore {
    override fun load(): String? = prefs.getString(KEY, null)

    override fun save(text: String): Boolean = prefs.edit().putString(KEY, text).commit()

    companion object {
        const val FILE = "portico_sizes"
        const val KEY = "sizes_v1"
    }
}
