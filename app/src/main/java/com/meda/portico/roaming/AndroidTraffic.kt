package com.meda.portico.roaming

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.meda.portico.core.traffic.Medium
import com.meda.portico.core.traffic.MobileSampler
import com.meda.portico.core.traffic.TrafficMeter
import com.meda.portico.core.traffic.TrafficStore
import com.meda.portico.core.traffic.TrafficTally
import java.time.Instant
import java.time.ZoneId

/**
 * A-10: the traffic meter's Android side. `:core` ([TrafficMeter], [MobileSampler]) holds the ledger and the arithmetic; this feeds it.
 *
 * What is counted, and what is not (stated plainly because a meter that overclaims is worse than none):
 * - **Total** = Android's own count for this app's UID (`TrafficStats.getUidRxBytes/TxBytes`: everything the process
 *   moved, tunnel overhead and tailnet control traffic included), sampled every minute and just BEFORE each default-network change, and
 *   charged to the medium that was current for the interval: mobile (roaming included) or, since 0.6.70, Wi-Fi (Ethernet included).
 *   Any other network (VPN alone, none) charges nothing.
 * - **Video / calls** = payload bytes the app counted where each stream is recognisable ([TrafficTally]): RTSP video, camera stills,
 *   call-audio frames over the link. WebRTC at home is not counted (it is in the OS total, so "other" on Wi-Fi includes it).
 *   "Everything else" is the remainder.
 * - **Not counted:** bytes moved while this process was not running (at most the last minute is lost at a process death), and the
 *   tiny share of traffic while the OS counter is `UNSUPPORTED`.
 * The system's own data-usage screen remains the authority for the bill.
 *
 * UNVERIFIED ON A PHONE: that `TrafficStats` reports a UID count on the target device, and that the default-network callback fires
 * when the radio changes (the intercom app measured with the same two sources).
 */
class AndroidTraffic(private val app: Context, prefs: SharedPreferences) {
    val meter = TrafficMeter(PrefsTrafficStore(prefs), ::dayOf)
    private val sampler = MobileSampler(meter)
    private val main = Handler(Looper.getMainLooper())
    private var medium: Medium? = null
    private var started = false

    /** Registers the default-network callback and the one-minute timer. Idempotent; never throws. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        try {
            val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
            medium = mediumOf(cm.getNetworkCapabilities(cm.activeNetwork))
            sampleLocked() // the baseline
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed(mediumOf(caps))
                override fun onLost(network: Network) = changed(null)
            })
            main.postDelayed(object : Runnable {
                override fun run() {
                    sample()
                    main.postDelayed(this, TICK_MS)
                }
            }, TICK_MS)
        } catch (_: Exception) {
            // a meter that cannot start is simply absent
        }
    }

    /** Fold what has been moved so far into the ledger (Settings calls it before it reads the numbers). */
    @Synchronized
    fun sample() = sampleLocked()

    @Synchronized
    private fun changed(now: Medium?) {
        if (now == medium) return
        sampleLocked() // the interval that just ended belongs to the old network
        medium = now
    }

    private fun sampleLocked() {
        try {
            sampler.sample(System.currentTimeMillis(), medium, osBytes(), TrafficTally.video(), TrafficTally.audio())
        } catch (_: Exception) {
            // never a crash from a counter
        }
    }

    private fun osBytes(): Long? {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        return if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) null else rx + tx
    }

    /** Wi-Fi (or Ethernet) is [Medium.WIFI], as AndroidRoaming.defaultNet classes it; cellular alone is [Medium.MOBILE]; anything else is not metered. */
    private fun mediumOf(c: NetworkCapabilities?): Medium? = when {
        c == null -> null
        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Medium.WIFI
        c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Medium.MOBILE
        else -> null
    }

    private class PrefsTrafficStore(private val prefs: SharedPreferences) : TrafficStore {
        override fun read(): String = prefs.getString(KEY, "").orEmpty()
        override fun write(text: String) {
            prefs.edit().putString(KEY, text).apply()
        }
    }

    companion object {
        const val FILE = "portico_traffic"
        private const val KEY = "ledger_v1"
        private const val TICK_MS = 60_000L

        private fun dayOf(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }
}
