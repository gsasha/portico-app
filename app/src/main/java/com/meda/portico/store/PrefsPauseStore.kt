package com.meda.portico.store

import android.content.SharedPreferences
import com.meda.portico.core.pause.PauseState
import com.meda.portico.core.pause.PauseStore

/**
 * Pause on its own private prefs file ([FILE], MODE_PRIVATE): paused, since, until, and when the
 * last pause ended. Every write is a synchronous `commit()`, made BEFORE anything is torn down,
 * so a process killed mid-teardown comes back still paused (and one killed mid-resume comes back
 * resumed). Plain, not encrypted: three timestamps and a flag, read on the push hot path.
 *
 * Reads may throw (a value of the wrong type): `PauseKeeper` turns any exception into "not
 * paused" (fail open). Only the [SharedPreferences] interface is used, so the JVM tests use a fake.
 */
class PrefsPauseStore(private val prefs: SharedPreferences) : PauseStore {

    override fun load(): PauseState {
        if (!prefs.getBoolean(K_PAUSED, false)) return PauseState.NOT_PAUSED
        return PauseState(true, prefs.getLong(K_SINCE, 0L), prefs.getLong(K_UNTIL, 0L))
    }

    override fun save(state: PauseState): Boolean =
        prefs.edit().putBoolean(K_PAUSED, state.paused).putLong(K_SINCE, state.sinceMs).putLong(K_UNTIL, state.untilMs).commit()

    override fun saveResumed(resumedAtMs: Long): Boolean =
        prefs.edit().putBoolean(K_PAUSED, false).remove(K_SINCE).remove(K_UNTIL).putLong(K_RESUMED_AT, resumedAtMs).commit()

    override fun loadResumedAt(): Long = prefs.getLong(K_RESUMED_AT, 0L)

    companion object {
        const val FILE = "portico_pause"
        private const val K_PAUSED = "paused"
        private const val K_SINCE = "since"
        private const val K_UNTIL = "until"
        private const val K_RESUMED_AT = "resumed_at"
    }
}
