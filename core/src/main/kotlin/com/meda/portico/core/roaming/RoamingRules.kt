package com.meda.portico.core.roaming

/**
 * A-10 / DEV-702: what the phone knows about its data connection, reduced to plain values (no `android.*`). Ported from the intercom
 * app's `RoamingRules` (0.33.0 to 0.35.6), including its lessons: a travel eSIM is NOT roaming but is not home either, so the country
 * is compared; "no network right now" is "don't know", never "home"; Wi-Fi is never metered.
 */
data class RoamingFacts(
    /** Provably at home: the link to Home Assistant is up over the direct (LAN) path while a Wi-Fi or cable network exists. Asked FIRST. */
    val lanVerified: Boolean,
    /** There is a default network at all. */
    val haveDefaultNet: Boolean,
    /** The DEFAULT network is Wi-Fi or wired. */
    val wifiOrEthernet: Boolean,
    /** The DEFAULT network is mobile. */
    val cellular: Boolean,
    /** The mobile network claims `NET_CAPABILITY_NOT_ROAMING`. */
    val notRoamingCap: Boolean,
    /** `TelephonyManager.isNetworkRoaming`. */
    val telephonyRoaming: Boolean,
    /** The estate's country, lower case, possibly "" (never learned). The [RoamingGate] fills it in. */
    val homeIso: String = "",
    /** The serving network's country, lower case, possibly "" (no signal, no SIM, flight mode). */
    val hereIso: String = "",
    /** A Wi-Fi is joined but Android has not made it the default yet (changes the sentence only). */
    val wifiJoinedNotDefault: Boolean = false,
)

/** The answer and the sentence that explains it; [keepPrevious] means "don't know": the caller keeps its last verdict. */
data class RoamingVerdict(val away: Boolean, val why: String, val keepPrevious: Boolean = false)

/**
 * The away-from-home verdict, pure. The ORDER of the tests is the specification: every early return is a case that must not reach
 * the mobile tests below it. `RoamingRulesTest` walks every row.
 */
object RoamingRules {
    fun judge(f: RoamingFacts): RoamingVerdict {
        val v = decide(f)
        if (!v.away || !f.wifiJoinedNotDefault) return v
        return RoamingVerdict(
            true,
            v.why + " (the Wi-Fi is joined but Android has not made it the route yet, so the bytes still leave over mobile; this clears by itself in a few seconds)",
        )
    }

    private fun decide(f: RoamingFacts): RoamingVerdict {
        // 1 The estate's own Wi-Fi ends it. With the WAN down Android makes cellular the default network, so every test below would
        //   say "mobile data" about a phone standing in the kitchen.
        if (f.lanVerified) return RoamingVerdict(false, "at home: Home Assistant answered over the local network")
        // 2 No data connection is not an answer (0.35.1): hold the last verdict. Reading it as "home" unblocked the picture in the gap
        //   between one network going and the next arriving.
        if (!f.haveDefaultNet) return RoamingVerdict(false, "no data connection at all, holding the last verdict", keepPrevious = true)
        // 3 Wi-Fi is never metered, foreign hotel Wi-Fi included.
        if (f.wifiOrEthernet) return RoamingVerdict(false, "data is going over Wi-Fi or a cable, nothing is metered")
        // 4 Not mobile (a tablet, a VPN-only oddity): not this rule's business.
        if (!f.cellular) return RoamingVerdict(false, "not a mobile connection")
        // 5 the ordinary case
        if (!f.notRoamingCap) return RoamingVerdict(true, "the mobile network reports roaming")
        // 6 the same question asked of telephony (fed from a different place)
        if (f.telephonyRoaming) return RoamingVerdict(true, "the SIM carrying data says it is roaming")
        val home = iso(f.homeIso)
        val here = iso(f.hereIso)
        // 7 the local-eSIM case: nothing is roaming, it is still not home, and it is the tight allowance
        if (home.isNotEmpty() && here.isNotEmpty() && here != home) {
            return RoamingVerdict(true, "mobile data in ${here.uppercase()}, home is ${home.uppercase()}: a local SIM abroad is not roaming but it is not home")
        }
        // 8 the honest gap: nothing to compare, no flag set. Say home (blanking a phone in the house is what gets the feature switched
        //   off) and say which gap it fell into.
        if (here.isEmpty()) return RoamingVerdict(false, "mobile data, and the network does not say which country it is in: cannot tell, so pictures stay on")
        if (home.isEmpty()) return RoamingVerdict(false, "mobile data in ${here.uppercase()}, but no home country is learned yet: cannot tell, so pictures stay on")
        return RoamingVerdict(false, "mobile data on the home network (${here.uppercase()})")
    }

    fun iso(s: String?): String = s?.trim()?.lowercase().orEmpty()
}
