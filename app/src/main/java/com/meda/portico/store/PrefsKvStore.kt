package com.meda.portico.store

import android.content.SharedPreferences
import com.meda.portico.core.call.KvStore

/** DEV-811: [KvStore] over a plain prefs file (the call-audio choices and the wideband mark; nothing secret). Every write commits. */
class PrefsKvStore(private val prefs: SharedPreferences) : KvStore {
    override fun getLong(key: String, default: Long): Long = try { prefs.getLong(key, default) } catch (_: Exception) { default }
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).commit() }
    override fun getString(key: String, default: String?): String? = try { prefs.getString(key, default) } catch (_: Exception) { default }
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).commit() }
    override fun remove(key: String) { prefs.edit().remove(key).commit() }

    companion object {
        const val FILE = "portico_callaudio"
    }
}
