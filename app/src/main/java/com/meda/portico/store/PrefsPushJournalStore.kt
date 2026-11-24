package com.meda.portico.store

import android.content.SharedPreferences
import com.meda.portico.core.push.PushJournalStore

/**
 * The push and ring journal's durable text (0.5.12-alpha): ONE string in its own plain prefs file, so it survives the
 * process dying right after `onMessageReceived` returns. [write] is a synchronous `commit()`: SharedPreferences writes a
 * new file and renames it over the old one, so a reader sees the old text or the new, never half of either. Not the
 * encrypted [Settings] file: the text holds shortened ids and decision words only (see `PushJournalFormat`), and the
 * Keystore must not stand between a ring and its record. `allowBackup="false"` keeps the file on the phone.
 */
class PrefsPushJournalStore(private val prefs: SharedPreferences) : PushJournalStore {
    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(text: String): Boolean = prefs.edit().putString(KEY, text).commit()

    companion object {
        const val FILE = "portico_push_journal"
        const val KEY = "journal_v1"
    }
}
