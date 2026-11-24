package com.meda.portico.store

import android.content.SharedPreferences
import com.meda.portico.core.content.ContentCodec
import com.meda.portico.core.content.ContentStore
import com.meda.portico.core.ha.ContentProfile

/**
 * The last content profile (cameras and buttons by name), on the plain [PrefsStateFile] prefs beside
 * the doors cache: names, ids and flags only, so nothing here is secret ([ContentCodec]), and the
 * Home screen has something to show before the socket is up. Every write is a synchronous `commit()`.
 */
class PrefsContentStore(private val prefs: SharedPreferences) : ContentStore {
    override fun load(): ContentProfile? = ContentCodec.decode(prefs.getString(KEY, null))

    override fun save(profile: ContentProfile) {
        prefs.edit().putString(KEY, ContentCodec.encode(profile)).commit()
    }

    override fun clear() {
        prefs.edit().remove(KEY).commit()
    }

    companion object {
        const val KEY = "content_v1"
    }
}
