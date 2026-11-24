package com.meda.portico.tailnet

import java.net.NetworkInterface
import java.util.Collections

/**
 * The network interface table for the Go side (DEV-557), ported from the intercom app's
 * `Interfaces.java`, which has fed tsnet on Android 11+ phones since 2026-09-07.
 *
 * Why it exists: Go enumerates interfaces with a NETLINK_ROUTE dump, which Android 11 (SDK 30)
 * blocked for apps, so `net.Interfaces()` fails with "permission denied" and tsnet cannot start.
 * `java.net.NetworkInterface` still works (the framework answers it through a permitted path), so
 * the table is built here and handed to `gocore/tailnet.SetInterfaces`, which serves it to
 * `tailscale.com/net/netmon.RegisterInterfaceGetter` (Tailscale's own supported mechanism).
 *
 * One interface per line: `name \t index \t mtu \t flags \t ip/prefix,ip/prefix`. flags are Go's
 * `net.Flags` bits. No `android.*` imports, so it is JVM-tested on the build host.
 */
object TailnetInterfaces {
    // Go's net.Flags bits. Named because a wrong bit here is a bug that looks like a network problem.
    const val FLAG_UP = 1
    const val FLAG_BROADCAST = 2
    const val FLAG_LOOPBACK = 4
    const val FLAG_POINTTOPOINT = 8
    const val FLAG_MULTICAST = 16
    const val FLAG_RUNNING = 32

    fun table(): String = buildString {
        val all = NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) } ?: emptyList()
        for (ni in all) append(line(ni)).append('\n')
    }

    internal fun line(ni: NetworkInterface): String {
        var flags = 0
        try {
            if (ni.isUp) flags = flags or FLAG_UP or FLAG_RUNNING
            if (ni.isLoopback) flags = flags or FLAG_LOOPBACK
            if (ni.isPointToPoint) flags = flags or FLAG_POINTTOPOINT
            if (ni.supportsMulticast()) flags = flags or FLAG_MULTICAST
            // Android exposes no isBroadcast(); infer it the way ordinary Ethernet/Wi-Fi behaves.
            if (!ni.isLoopback && !ni.isPointToPoint) flags = flags or FLAG_BROADCAST
        } catch (_: Exception) {
        }
        val mtu = try {
            ni.mtu
        } catch (_: Exception) {
            1500
        }
        val addrs = ni.interfaceAddresses.mapNotNull { ia ->
            val host = ia.address?.hostAddress ?: return@mapNotNull null
            // Java appends %scope to link-local IPv6; Go's ParseCIDR rejects it and drops the address.
            host.substringBefore('%') + "/" + ia.networkPrefixLength
        }
        return listOf(ni.name, ni.index.toString(), mtu.toString(), flags.toString(), addrs.joinToString(",")).joinToString("\t")
    }

    // ---- A-08 (DEV-761): what counts as "the network", ported from the intercom app's IfaceTable ------------

    /** The interface names in a table (first field of each line). */
    fun names(table: String): Set<String> =
        table.lineSequence().mapNotNull { l -> l.substringBefore('\t', "").ifEmpty { null } }.toSortedSet()

    /**
     * Keep only the interfaces that carry the DEFAULT network (plus loopback). With Wi-Fi and cellular both up the old app
     * advertised twelve endpoints on two ISPs and the WireGuard handshake never completed (0.34.0): a packet sourced from one
     * network's address and sent out another's route is dropped. [keep] is the default network's interface and its 464XLAT
     * `v4-` translator. An empty [keep] (undeterminable) or a result with no global address returns [table] unchanged:
     * tsnet with nowhere to dial is worse than the churn this cures.
     */
    fun restrict(table: String, keep: Set<String>): String {
        if (keep.isEmpty()) return table
        val out = StringBuilder()
        var anyGlobal = false
        for (line in table.split('\n')) {
            if (line.isEmpty()) continue
            val name = line.substringBefore('\t')
            val loop = name.startsWith("lo")
            if (name !in keep && !loop) continue
            out.append(line).append('\n')
            val f = line.split('\t')
            if (f.size >= 5 && f[4].isNotEmpty() && !loop) {
                for (a in f[4].split(',')) if (!a.startsWith("fe80:") && !a.startsWith("127.")) anyGlobal = true
            }
        }
        return if (anyGlobal) out.toString() else table
    }

    /**
     * The stable shape of a table: what must change before it counts as a different network (0.33.7). Name, flags, MTU, IPv4
     * addresses whole, IPv6 reduced to its /64 prefix, so a rotating privacy address (re-minted every few seconds) is not a
     * network change. The full table still goes to Go; only the "did it change?" question uses this.
     */
    fun signature(table: String): String {
        val parts = java.util.TreeSet<String>()
        for (line in table.split('\n')) {
            val f = line.split('\t')
            if (f.size < 5) continue
            val addrs = java.util.TreeSet<String>()
            for (a in f[4].split(',')) {
                if (a.isEmpty()) continue
                val ip = a.substringBefore('/')
                if (':' !in ip) {
                    addrs.add(a)
                    continue
                }
                addrs.add(ip.split(':').take(4).joinToString(":") + "::/64")
            }
            parts.add("${f[0]}|${f[2]}|${f[3]}|$addrs")
        }
        return parts.toString()
    }

    /** Did an interface of [old] disappear from [new]? (0.33.8: a wlan0 missing for ~2.5 s is not a lost Wi-Fi.) */
    fun vanished(old: String?, new: String): Boolean {
        if (old.isNullOrEmpty()) return false
        val now = names(new)
        return names(old).any { it !in now }
    }
}
