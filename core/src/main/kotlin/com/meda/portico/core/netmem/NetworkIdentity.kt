package com.meda.portico.core.netmem

import java.security.MessageDigest

/**
 * What the phone can tell about the Wi-Fi/Ethernet network it is on (DEV-694 Stage 2, design
 * section 10). Every field may be unknown. Pure values: the Android adapter fills it in.
 *
 * ⚠️ A WEAK, collision-prone identity. `192.168.0.0/24` with gateway `.1` is shared by thousands
 * of networks; the SSID (when the phone may read it, the owner's decision D4) discriminates better.
 * The design leans on the failure asymmetry and on [NetworkMemory]'s AMBIGUOUS state, not on this.
 *
 * @property ssid the Wi-Fi name, or null when unreadable (no permission, location off, Ethernet).
 * @property ipv4Subnet the phone's IPv4 address masked by its prefix, as `a.b.c.d/len`.
 * @property gateway the default gateway's address.
 * @property ipv6Prefix64 the GLOBAL IPv6 /64 as `xxxx:xxxx:xxxx:xxxx` (never link-local `fe80::`;
 *   optional, ISP prefix delegation can rotate it).
 */
data class NetworkIdentity(
    val ssid: String? = null,
    val ipv4Subnet: String? = null,
    val gateway: String? = null,
    val ipv6Prefix64: String? = null,
) {
    /**
     * A short stable key, or null when nothing identifies the network. A hash of low-entropy
     * input is NOT a defence (brute-forceable in seconds); it only keeps the stored file from
     * showing an address at a glance. The protection is the encrypted store (design 10).
     * An entry made without the SSID is a different key from one made with it.
     */
    fun key(): String? {
        val parts = listOf(ssid, ipv4Subnet, gateway, ipv6Prefix64)
        if (parts.all { it.isNullOrBlank() }) return null
        val canon = "v1|" + parts.joinToString("|") { (it ?: "").trim() }
        return MessageDigest.getInstance("SHA-256").digest(canon.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Never prints a field: this object is location-revealing. */
    override fun toString(): String = "NetworkIdentity(ssid=${if (ssid.isNullOrBlank()) "none" else "present"}, key=${key() ?: "none"})"
}
