package com.meda.portico.tailnet

import android.content.SharedPreferences
import com.meda.portico.core.ha.DirectOutsideStore

/** [DirectOutsideStore] in the app's plain state preferences: a boolean, a timestamp and a hash. Nothing secret. */
class PrefsDirectOutsideStore(private val prefs: SharedPreferences) : DirectOutsideStore {
    override var forKey: String?
        get() = prefs.getString(K_FOR, null)
        set(v) = prefs.edit().putString(K_FOR, v).apply()
    override var works: Boolean
        get() = prefs.getBoolean(K_WORKS, false)
        set(v) = prefs.edit().putBoolean(K_WORKS, v).apply()
    override var firstFailureAtMs: Long
        get() = prefs.getLong(K_FIRST, 0L)
        set(v) = prefs.edit().putLong(K_FIRST, v).apply()

    private companion object {
        const val K_FOR = "direct_outside_for"
        const val K_WORKS = "direct_outside_works"
        const val K_FIRST = "direct_outside_first_failure_ms"
    }
}
