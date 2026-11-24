package com.meda.portico.core.roaming

/** Where the learned home country lives (the app: SharedPreferences; the tests: a field). May throw; the gate fences it. */
interface HomeCountryStore {
    fun get(): String
    fun set(iso: String)
}

/**
 * The stateful half of the roaming block (A-10 / DEV-702), over [RoamingRules]: the learned home country, the hold across "don't
 * know", and the per-trip consent. Copies the intercom app's rules:
 * - **Default: blocked.** Away and not yet allowed means the picture (live video and stills) is held back; the ring, the call
 *   signalling and call audio are never gated (an intercom you cannot answer from abroad is a broken one).
 * - **A tap lasts for the trip**, not for the network: it lapses only when the verdict says the phone is NOT away (not on "don't know").
 *   Held in memory only, like the old app: a restarted process starts blocked again.
 * - **The home country is LEARNED** from the first provably-at-home moment with a serving country, and starts empty.
 * - **Fails open**: any error reads as "not blocking".
 * Thread-safe.
 */
class RoamingGate(private val store: HomeCountryStore, private val log: (String) -> Unit = {}) {
    private var last = RoamingVerdict(false, "not checked yet")
    private var allowed = false

    /** Feeds the phone's current facts and returns the verdict that stands (the previous one across a "don't know"). */
    @Synchronized
    fun update(f: RoamingFacts): RoamingVerdict {
        try {
            learn(f)
            val v = RoamingRules.judge(f.copy(homeIso = safeHome()))
            if (v.keepPrevious) return last
            if (!v.away) allowed = false // the trip is over
            if (v.away != last.away) log("roaming: ${if (v.away) "away" else "home"}, ${v.why}")
            last = v
            return v
        } catch (_: Exception) {
            return last
        }
    }

    /** True when the picture must not be fetched now: away and not allowed for this trip. */
    @Synchronized
    fun blocking(): Boolean = last.away && !allowed

    /** The user tapped "show anyway": the rest of this trip is allowed. */
    @Synchronized
    fun allowTrip() {
        if (last.away && !allowed) {
            allowed = true
            log("roaming: pictures allowed for this trip")
        }
    }

    @Synchronized
    fun tripAllowed(): Boolean = allowed

    @Synchronized
    fun verdict(): RoamingVerdict = last

    private fun safeHome(): String = try {
        RoamingRules.iso(store.get())
    } catch (_: Exception) {
        ""
    }

    private fun learn(f: RoamingFacts) {
        val here = RoamingRules.iso(f.hereIso)
        if (!f.lanVerified || here.isEmpty() || here == safeHome()) return
        try {
            store.set(here)
            log("roaming: home country learned as ${here.uppercase()}")
        } catch (_: Exception) {
            // not learned this time
        }
    }
}
