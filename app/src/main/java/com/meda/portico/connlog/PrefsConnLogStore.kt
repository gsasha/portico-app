package com.meda.portico.connlog

import android.content.SharedPreferences
import com.meda.portico.core.connlog.ConnLogStore

/** The connection-log queue in plain preferences: a report holds nothing secret (design section 3). */
class PrefsConnLogStore(private val prefs: SharedPreferences) : ConnLogStore {
    override fun read(): String? = prefs.getString(KEY, null)
    override fun write(text: String) {
        prefs.edit().putString(KEY, text).apply()
    }

    override fun writeDurable(text: String) {
        prefs.edit().putString(KEY, text).commit() // synchronous: the crash handler's write must outlive the process
    }

    companion object {
        const val FILE = "portico_connlog"
        private const val KEY = "queue"

        /** "Send connection logs to Home Assistant": on unless the user switched it off. */
        const val KEY_ENABLED = "enabled"
    }
}
