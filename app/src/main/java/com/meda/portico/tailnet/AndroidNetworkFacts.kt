package com.meda.portico.tailnet

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.meda.portico.core.ha.NetInfo
import com.meda.portico.core.ha.NetSnapshot

/**
 * DEV-694 P1c: what Android says about the phone's networks, reduced to a [NetSnapshot]. All the
 * meaning is in `:core`'s `PathFactsReader` (JVM-tested); this only reads `ConnectivityManager`
 * (`ACCESS_NETWORK_STATE`, already in the manifest), on demand, once per connect: a cheap local
 * call, so no callbacks and no cache. Any failure is null, which the hint treats as "today's
 * behaviour" (fail open).
 *
 * The intercom app's lessons, kept: ask about ALL networks, not only the default (with the WAN
 * down Android demotes the house Wi-Fi and cellular becomes the default, `Net.local`), and a
 * VPN network is listed and flagged, never mistaken for the transport under it.
 */
class AndroidNetworkFacts(private val app: Context) {

    @Suppress("DEPRECATION") // getAllNetworks: deprecated since API 31, still the only call that lists every network
    fun snapshot(): NetSnapshot? = try {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            null
        } else {
            NetSnapshot(
                networks = cm.allNetworks.mapNotNull { info(cm, it) },
                defaultNet = cm.activeNetwork?.let { info(cm, it) },
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun info(cm: ConnectivityManager, n: Network): NetInfo? {
        val c = cm.getNetworkCapabilities(n) ?: return null // can vanish between the two calls
        return NetInfo(
            wifiOrEthernet = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            cellular = c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            vpn = c.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            validated = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
    }
}
