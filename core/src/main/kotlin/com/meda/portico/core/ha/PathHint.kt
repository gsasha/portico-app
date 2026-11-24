package com.meda.portico.core.ha

/**
 * What the phone knows about its network, as plain values (DEV-694, `portico_path_hint_design.md`
 * section 5.2). Gathered by an Android adapter (P1c); this class and [PathHint] are pure, so
 * every rule is a row of a JVM test, like the intercom app's `RoamingRules`.
 *
 * @property paused the phone is paused (nothing connects).
 * @property tailnetNeeded the tailnet could help and may be started: it is usable, the phone has
 *   a stored identity with no pending single-use key, and the direct path is not known to work
 *   from outside the house (design section 4).
 * @property haveDefaultNet Android has a default network at all.
 * @property hasVpnDefault the default network carries `TRANSPORT_VPN` (another app's VPN).
 * @property hasCellularDefault the default network is mobile data.
 * @property validatedWifiOrEthernetExists a Wi-Fi/Ethernet network that Android has validated
 *   exists, default or not. With the WAN down Android demotes the house Wi-Fi and makes cellular
 *   the default, so "the default is cellular" does not mean "not on the LAN" (the intercom app's
 *   `Net.local`); only a *validated* one counts, or a saved captive or out-of-range Wi-Fi would
 *   switch the benefit off.
 * @property wifiOrEthernetExists any Wi-Fi/Ethernet network exists, validated or not (a superset
 *   of the above); only its absence makes the phone "mobile only".
 */
data class PathFacts(
    val paused: Boolean = false,
    val tailnetNeeded: Boolean = false,
    val haveDefaultNet: Boolean = true,
    val hasVpnDefault: Boolean = false,
    val hasCellularDefault: Boolean = false,
    val validatedWifiOrEthernetExists: Boolean = false,
    val wifiOrEthernetExists: Boolean = validatedWifiOrEthernetExists,
    /**
     * The last socket on THIS network (the same Android default-network id) took the tailnet, so the direct path did not win here. Cleared
     * by a new network and by a direct win. A reconnect on it starts the tailnet at once instead of after the direct deadline.
     */
    val tailnetWonHere: Boolean = false,
)

/** The two Stage 1 modes. `DIRECT_FIRST` is exactly today's behaviour. */
enum class PathMode {
    /** Direct first, the tailnet only when it fails or is slow. */
    DIRECT_FIRST,

    /** Start the tailnet at once, alongside the direct attempt; the first to open wins. */
    TAILNET_FIRST,
}

/**
 * The verdict and the sentence that explains it (the reason travels WITH the verdict, or it
 * drifts from it). [mobileOnly]: cellular is the default, no Wi-Fi/Ethernet network exists at
 * all, and no VPN is involved. It is **independent of [mode]** (and of `paused` and
 * `tailnetNeeded`): the connector uses it to learn whether the direct path works from outside the
 * house, and that flag could never be cleared if a DIRECT_FIRST attempt did not report it.
 */
data class Hint(
    val mode: PathMode,
    val why: String,
    val mobileOnly: Boolean = false,
    /** For [PathMode.TAILNET_FIRST]: how long the direct attempt racing the tailnet may live. Null: the connector's own deadline. */
    val directCapMs: Long? = null,
)

/**
 * Stage 1 of the path hint. The ORDER of the rules is part of the specification, as in the
 * intercom app's `RoamingRules`: every early return is a case that must not reach the rules below.
 * FAIL OPEN: anything unexpected is [PathMode.DIRECT_FIRST], today's behaviour.
 */
object PathHint {
    /** The direct attempt of a tailnet-first connect on a network where the tailnet won last time lives this long at most. */
    const val DIRECT_CAP_MS = 3_000L

    fun judge(f: PathFacts): Hint {
        val mobileOnly = f.haveDefaultNet && f.hasCellularDefault && !f.wifiOrEthernetExists && !f.hasVpnDefault

        // 0 · Nothing to hint: paused, or the tailnet cannot or need not help.
        if (f.paused) return Hint(PathMode.DIRECT_FIRST, "paused: nothing connects", mobileOnly)
        if (!f.tailnetNeeded) return Hint(PathMode.DIRECT_FIRST, "the tailnet is not needed or not usable here", mobileOnly)

        // 1 · No default network: nothing can connect; the next change of network asks again.
        if (!f.haveDefaultNet) return Hint(PathMode.DIRECT_FIRST, "no network at all", mobileOnly)

        // 2 · Another app's VPN is the default: direct may legitimately work through it, and the
        //     transports underneath it cannot be trusted. (Portico's own tsnet is userspace and
        //     never appears here.) Never "mobile only": a VPN declares its underlying cellular.
        if (f.hasVpnDefault) return Hint(PathMode.DIRECT_FIRST, "another VPN carries the default network", mobileOnly)

        // 3 · Mobile data and no validated Wi-Fi/Ethernet anywhere: this is not the home LAN. A
        //     fact, so the tailnet starts at once. A validated Wi-Fi that is merely not the default
        //     (the dead-WAN house) is rule 4, not here.
        if (f.hasCellularDefault && !f.validatedWifiOrEthernetExists) {
            return Hint(PathMode.TAILNET_FIRST, "mobile data, no validated Wi-Fi: not the home LAN", mobileOnly)
        }

        // 3b · The last socket here was the tailnet (a Wi-Fi where the home name does not resolve or answer): do not make the tailnet
        //      wait out the direct attempt again (a measured 20 s DNS timeout before the tailnet was even tried). Direct still races
        //      alongside, capped, so a Wi-Fi that has become the house again still wins.
        if (f.tailnetWonHere) return Hint(PathMode.TAILNET_FIRST, "the last connection on this network took the tailnet", mobileOnly, DIRECT_CAP_MS)

        // 4 · Everything else, every Wi-Fi included: today's behaviour. (Stage 2 refines Wi-Fi.)
        return Hint(PathMode.DIRECT_FIRST, "a Wi-Fi or other network: direct first", mobileOnly)
    }
}
