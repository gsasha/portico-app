package com.meda.portico.stay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.NetKind
import com.meda.portico.core.stay.StayConnectedPolicy

/**
 * The default network's kind for "Stay connected", read synchronously ([current], so the first
 * decision of a process never sees "no network" just because no callback has fired yet) and
 * reported on change ([onChange], from the connectivity thread). Makes no network call.
 * Classification is `:core`'s [StayConnectedPolicy.kindOf]: Wi-Fi under a VPN is Wi-Fi.
 */
class StayNetWatcher(private val context: Context, private val log: HaLog, private val onChange: () -> Unit) {
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var last: NetKind? = null

    fun current(): NetKind = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        if (caps == null) NetKind.NONE else kindOf(caps)
    } catch (e: Exception) {
        NetKind.OTHER
    }

    @Synchronized
    fun start() {
        if (callback != null) return
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed(kindOf(caps))
                override fun onLost(network: Network) = changed(NetKind.NONE)
            }
            cm.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (e: Exception) {
            log.log(HaLog.Level.WARN, "stay: network callback failed: ${e.javaClass.simpleName}")
        }
    }

    /** Only a change of KIND matters (signal-strength updates arrive here too). */
    private fun changed(kind: NetKind) {
        if (kind == last) return
        last = kind
        runCatching(onChange)
    }

    private fun kindOf(caps: NetworkCapabilities) = StayConnectedPolicy.kindOf(
        wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
    )
}
