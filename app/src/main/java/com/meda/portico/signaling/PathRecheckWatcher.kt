package com.meda.portico.signaling

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.NetKind
import com.meda.portico.core.ha.PathRechecker

/**
 * Feeds the default network to [PathRechecker] (`:core`), which decides whether an open tailnet
 * socket should be re-routed direct-first. This class only translates Android's callbacks:
 * `registerDefaultNetworkCallback` (API 24; minSdk is 26) needs `ACCESS_NETWORK_STATE`, already
 * declared. It makes no network call, so a paused phone stays quiet (the rechecker refuses while
 * paused). [start] is idempotent; [stop] unregisters.
 */
class PathRecheckWatcher(
    private val context: Context,
    private val rechecker: PathRechecker,
    private val log: HaLog = HaLog.NONE,
) {
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * A network change in Doze wakes the CPU only for the callback; the reconnect then hops to the timer thread, the signaling
     * thread and the socket, and the phone can fall asleep between the hops (a measured Wi-Fi join was evaluated 5.5 s late, when
     * the next callback woke it, DEV-705). A short partial wake lock, released by its own timeout, holds the CPU through the hops.
     */
    private val wake: PowerManager.WakeLock? = try {
        context.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "portico:netchange")
            ?.apply { setReferenceCounted(false) }
    } catch (_: Exception) {
        null
    }

    private fun hold() {
        try {
            wake?.acquire(WAKE_MS)
        } catch (_: Exception) {
            // no wake lock costs the Doze speed-up, never the connect
        }
    }

    @Synchronized
    fun start() {
        if (callback != null) return
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    hold()
                    rechecker.onNetwork(network.networkHandle, kindOf(caps))
                }

                override fun onLost(network: Network) {
                    hold()
                    rechecker.onNetwork(0L, NetKind.NONE)
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (e: Exception) {
            log.log(HaLog.Level.WARN, "path recheck: network callback failed: ${e.javaClass.simpleName}")
        }
    }

    @Synchronized
    fun stop() {
        val cb = callback ?: return
        callback = null
        try {
            context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    private fun kindOf(caps: NetworkCapabilities) = NetKind.of(
        wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
    )

    private companion object {
        /** Long enough for a reconnect (direct, then the tailnet) to be under way, short enough to be harmless. */
        const val WAKE_MS = 15_000L
    }
}
