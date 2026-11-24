package com.meda.portico.core.ha

import java.security.MessageDigest

/** Where [DirectWorksOutside] keeps its state across process death (the app: plain, non-secret preferences). */
interface DirectOutsideStore {
    /** Which enrollment (a hash of `ha_url` and `ha_dial`) the flag was learned for; null: none. */
    var forKey: String?
    var works: Boolean

    /** When the first unreachable result on a mobile-only connect was seen; 0: none pending. */
    var firstFailureAtMs: Long
}

/**
 * Does the direct path to Home Assistant work from OUTSIDE the house? (DEV-694 P1c, design
 * section 4.) If it does (a public `ha_url`, no split-horizon DNS) the tailnet is not needed and
 * the hint stays quiet; if it is unknown the first mobile-only connect finds out, so a public
 * `ha_url` pays for one speculative tailnet start, once.
 *
 * - [opened]: a direct socket opened while the phone was mobile-only: it works.
 * - [unreachable]: a direct attempt failed for a REACHABILITY reason (never TLS, auth or HTTP) while
 *   mobile-only. Two such results at least [CLEAR_AFTER_MS] apart clear the flag, so one blip
 *   (a lift, a tunnel) does not flip the install's behaviour; any [opened] resets the first.
 * - A different enrollment (`ha_url`/`ha_dial` changed) starts over: the flag is unknown.
 *
 * Stores only a boolean, a timestamp and a hash of the enrollment: no address, key or token.
 */
class DirectWorksOutside(
    private val store: DirectOutsideStore,
    /** `ha_url` and `ha_dial` as one string, or null when not enrolled. Hashed before it is stored. */
    private val enrollment: () -> String?,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    /** True when direct is known to work from outside for the CURRENT enrollment. */
    fun known(): Boolean = synchronized(lock) { store.works && store.forKey == keyNow() && keyNow() != null }

    fun opened() = synchronized(lock) {
        val k = keyNow() ?: return@synchronized
        store.forKey = k
        store.works = true
        store.firstFailureAtMs = 0
    }

    fun unreachable() = synchronized(lock) {
        val k = keyNow() ?: return@synchronized
        if (store.forKey != k) { // a different enrollment: nothing learned there applies
            store.forKey = k
            store.works = false
            store.firstFailureAtMs = 0
            return@synchronized
        }
        if (!store.works) return@synchronized
        val now = clockMs()
        val first = store.firstFailureAtMs
        when {
            first == 0L || now < first -> store.firstFailureAtMs = now // the first result (or a clock that went back)
            now - first >= CLEAR_AFTER_MS -> {
                store.works = false
                store.firstFailureAtMs = 0
            }
            // else: the same event seen again within minutes
        }
    }

    private fun keyNow(): String? = enrollment()?.let { hash(it) }

    companion object {
        const val CLEAR_AFTER_MS = 10L * 60 * 1000

        fun hash(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}
