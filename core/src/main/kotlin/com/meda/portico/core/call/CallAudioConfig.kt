package com.meda.portico.core.call

/** A tiny key-value store (the app backs it with plain SharedPreferences); nothing here is a secret. */
interface KvStore {
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
    fun getString(key: String, default: String?): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/** Setup > "Call audio path": which transport a call uses ON THE HOME NETWORK (DEV-811 2.7). Automatic is today's behaviour. */
enum class PathChoice { AUTO, FRAMES, WEBRTC }

/** How the device stands with 16 kHz audio (DEV-811 2.1). */
enum class MarkState(val word: String) { NONE("none"), TEMP("temp"), PERMANENT("perm") }

/** The picked profile for one call and why 8 kHz was chosen (`none` = wideband). */
class AudioChoice(val wideband: Boolean, val why: String) {
    val profile: AudioProfile get() = if (wideband) AudioProfile.L16 else AudioProfile.PCMU

    companion object {
        val WIDEBAND = AudioChoice(true, "none")
    }
}

/**
 * The call-audio settings and the "wideband failed" mark (DEV-811 P2), over a [KvStore]:
 *
 * - **Compatibility** forces `pcmu` (8 kHz); default off (wideband). Toggling it clears the mark.
 * - **Path choice** (Automatic / Frames / WebRTC) applies on the home network only and resets to Automatic [PATH_TTL_MS] (24 h) after it was set.
 * - **The mark:** a start failure at 16 kHz marks the device for [MARK_TTL_MS] (24 h); a second one within [PERMANENT_WINDOW_MS]
 *   (7 days) of the first makes it permanent until the app is updated (the stored mark carries the app version) or Compatibility is toggled.
 */
class CallAudioConfig(
    private val store: KvStore,
    private val clockMs: () -> Long,
    private val appVersion: String,
) {
    fun compatibility(): Boolean = store.getLong(K_COMPAT, 0) == 1L

    /**
     * DEV-811: the link race (a second connection beside a silent one on a mobile or tailnet link instead of dropping it). On by default;
     * Setup's "Reconnect while ringing" = Classic turns it off, which is the strict 2 s drop-and-reconnect of 0.6.90 everywhere. Lives with the
     * call-audio choices (plain prefs, Compatibility section) so it works before the Keystore has opened. Fail-open: an unreadable store is "on".
     */
    fun linkRace(): Boolean = try { store.getLong(K_NO_RACE, 0) != 1L } catch (_: Exception) { true }

    fun setLinkRace(on: Boolean) = store.putLong(K_NO_RACE, if (on) 0 else 1)

    /**
     * Setup "Answer without unlocking" (the owner 2026-11-21): taking a door call from the lock screen connects it with no unlock. Default on
     * ([com.meda.portico.core.ring.AnswerLockPolicy.DEFAULT_ANSWER_WITHOUT_UNLOCK]); plain prefs; fail-open to the default.
     */
    fun answerWithoutUnlock(): Boolean = try {
        when (store.getLong(K_ANSWER_LOCKED, -1)) {
            0L -> false
            1L -> true
            else -> com.meda.portico.core.ring.AnswerLockPolicy.DEFAULT_ANSWER_WITHOUT_UNLOCK
        }
    } catch (_: Exception) { com.meda.portico.core.ring.AnswerLockPolicy.DEFAULT_ANSWER_WITHOUT_UNLOCK }

    fun setAnswerWithoutUnlock(on: Boolean) = store.putLong(K_ANSWER_LOCKED, if (on) 1 else 0)

    /**
     * Setup "Unlock when answering" (the owner 2026-11-21): after answering from the lock screen, the in-call screen asks the system for the
     * unlock once (face recognised unlocks; a fingerprint prompt unlocks on touch). Default on ([com.meda.portico.core.ring.InCallLock.DEFAULT_UNLOCK_WHEN_ANSWERING]);
     * plain prefs; fail-open to the default.
     */
    fun unlockWhenAnswering(): Boolean = try {
        when (store.getLong(K_UNLOCK_ANSWER, -1)) {
            0L -> false
            1L -> true
            else -> com.meda.portico.core.ring.InCallLock.DEFAULT_UNLOCK_WHEN_ANSWERING
        }
    } catch (_: Exception) { com.meda.portico.core.ring.InCallLock.DEFAULT_UNLOCK_WHEN_ANSWERING }

    fun setUnlockWhenAnswering(on: Boolean) = store.putLong(K_UNLOCK_ANSWER, if (on) 1 else 0)

    fun setCompatibility(on: Boolean) {
        store.putLong(K_COMPAT, if (on) 1 else 0)
        clearMark()
    }

    fun pathChoice(): PathChoice {
        val c = PathChoice.entries.firstOrNull { it.name == store.getString(K_PATH, null) } ?: return PathChoice.AUTO
        if (c == PathChoice.AUTO) return c
        val setAt = store.getLong(K_PATH_AT, 0)
        val now = clockMs()
        if (setAt <= 0 || now - setAt >= PATH_TTL_MS || now < setAt) {
            store.putString(K_PATH, PathChoice.AUTO.name)
            return PathChoice.AUTO
        }
        return c
    }

    fun setPathChoice(c: PathChoice) {
        store.putString(K_PATH, c.name)
        store.putLong(K_PATH_AT, clockMs())
    }

    fun mark(): MarkState {
        if (store.getString(K_MARK_VER, null) != appVersion) return MarkState.NONE
        if (store.getLong(K_MARK_PERM, 0) == 1L) return MarkState.PERMANENT
        val last = store.getLong(K_MARK_LAST, 0)
        val now = clockMs()
        return if (last > 0 && now >= last && now - last < MARK_TTL_MS) MarkState.TEMP else MarkState.NONE
    }

    /** A start failure at 16 kHz on this device. */
    fun recordWidebandFailure() {
        val now = clockMs()
        val sameVersion = store.getString(K_MARK_VER, null) == appVersion
        val first = if (sameVersion) store.getLong(K_MARK_FIRST, 0) else 0L
        store.putString(K_MARK_VER, appVersion)
        if (first > 0 && now >= first && now - first <= PERMANENT_WINDOW_MS) {
            store.putLong(K_MARK_PERM, 1)
        } else {
            store.putLong(K_MARK_FIRST, now)
            store.putLong(K_MARK_PERM, 0)
        }
        store.putLong(K_MARK_LAST, now)
    }

    private fun clearMark() {
        for (k in listOf(K_MARK_VER, K_MARK_FIRST, K_MARK_LAST, K_MARK_PERM)) store.remove(k)
    }

    /** What the next call uses: Compatibility, then the mark, else wideband. */
    fun choose(): AudioChoice = when {
        compatibility() -> AudioChoice(false, "compat")
        mark() != MarkState.NONE -> AudioChoice(false, "mark")
        else -> AudioChoice.WIDEBAND
    }

    companion object {
        const val PATH_TTL_MS = 24L * 3_600_000
        const val MARK_TTL_MS = 24L * 3_600_000
        const val PERMANENT_WINDOW_MS = 7L * 24 * 3_600_000
        private const val K_COMPAT = "callaudio_compat"
        private const val K_NO_RACE = "link_race_off"
        private const val K_ANSWER_LOCKED = "answer_without_unlock"
        private const val K_UNLOCK_ANSWER = "unlock_when_answering"
        private const val K_PATH = "callaudio_path"
        private const val K_PATH_AT = "callaudio_path_at"
        private const val K_MARK_VER = "callaudio_mark_ver"
        private const val K_MARK_FIRST = "callaudio_mark_first"
        private const val K_MARK_LAST = "callaudio_mark_last"
        private const val K_MARK_PERM = "callaudio_mark_perm"
    }
}
