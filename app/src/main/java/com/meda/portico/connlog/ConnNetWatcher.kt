package com.meda.portico.connlog

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.meda.portico.core.connlog.ConnNetMapper
import com.meda.portico.core.connlog.NetState
import com.meda.portico.core.ha.HaLog
import com.meda.portico.tailnet.AndroidNetworkFacts

/**
 * DEV-705: reports the phone's default network to the connection log, every time it changes. Reads
 * [AndroidNetworkFacts] (the same local `ConnectivityManager` read the path hint uses) and maps it with `:core`'s
 * [ConnNetMapper]; makes no network call. [nid] counts Android's network objects, so a roam between two Wi-Fi networks
 * (same kind) is still a visible change. The callback fires on every signal-strength update too; the log ignores a state
 * equal to the last one.
 */
class ConnNetWatcher(
    private val context: Context,
    private val facts: AndroidNetworkFacts,
    private val log: HaLog,
    private val onState: (NetState) -> Unit,
) {
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var nid = 0

    /** Which default network this is: it changes whenever Android picks a new one (a roam, a switch), and not otherwise. */
    val networkId: Int get() = nid

    /** The default network now, read synchronously (so a process's first sequence knows where it started). */
    fun current(): NetState = ConnNetMapper.of(facts.snapshot(), nid)

    @Synchronized
    fun start() {
        if (callback != null) return
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    nid++
                    emit()
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = emit()
                override fun onLost(network: Network) = emit()
            }
            cm.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (e: Exception) {
            log.log(HaLog.Level.WARN, "connlog: network callback failed: ${e.javaClass.simpleName}")
        }
    }

    private fun emit() {
        try {
            onState(current())
        } catch (_: Exception) {
            // the connection log must never disturb a connect
        }
    }
}
