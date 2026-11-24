package com.meda.portico.core.ha

/** One network as Android describes it, reduced to what the path hint needs. Pure values: no `android.*`. */
data class NetInfo(
    val wifiOrEthernet: Boolean,
    val cellular: Boolean,
    val vpn: Boolean,
    /** Android has validated it (`NET_CAPABILITY_VALIDATED`): it really reaches the internet. */
    val validated: Boolean,
)

/** Every network Android lists, and which one is the default (null: none). */
data class NetSnapshot(val networks: List<NetInfo>, val defaultNet: NetInfo?)

/**
 * Turns a [NetSnapshot] into [PathFacts] (DEV-694 P1c). The Android adapter only builds the
 * snapshot; this is where the meaning is, so it is tested on the JVM.
 */
object PathFactsReader {

    /**
     * [snapshot] null means "could not read the network": FAIL OPEN, the tailnet is not needed,
     * so the hint is [PathMode.DIRECT_FIRST], today's behaviour.
     */
    fun of(snapshot: NetSnapshot?, paused: Boolean, tailnetNeeded: Boolean, tailnetWonHere: Boolean = false): PathFacts {
        if (snapshot == null) return PathFacts(paused = paused, tailnetNeeded = false, haveDefaultNet = true)
        val d = snapshot.defaultNet
        val lan = snapshot.networks.filter { it.wifiOrEthernet && !it.vpn }
        return PathFacts(
            paused = paused,
            tailnetNeeded = tailnetNeeded,
            haveDefaultNet = d != null,
            hasVpnDefault = d?.vpn == true,
            hasCellularDefault = d?.cellular == true,
            validatedWifiOrEthernetExists = lan.any { it.validated },
            wifiOrEthernetExists = lan.isNotEmpty(),
            tailnetWonHere = tailnetWonHere,
        )
    }
}
