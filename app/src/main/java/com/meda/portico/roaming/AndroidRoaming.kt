package com.meda.portico.roaming

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.telephony.TelephonyManager
import com.meda.portico.core.netpolicy.NetClass
import com.meda.portico.core.netpolicy.NetworkPolicy
import com.meda.portico.core.roaming.HomeCountryStore
import com.meda.portico.core.roaming.RoamingFacts
import com.meda.portico.core.roaming.RoamingGate

/**
 * A-10 / DEV-702: the thin Android side of the roaming block. All the meaning is in `:core` ([com.meda.portico.core.roaming.RoamingRules],
 * [RoamingGate]); this only reads `ConnectivityManager` and `TelephonyManager` (no new permission: `ACCESS_NETWORK_STATE` is already
 * held, and neither `isNetworkRoaming` nor `getNetworkCountryIso` needs `READ_PHONE_STATE`).
 *
 * Pull-based with a short cache, like the intercom app's: 5 s, and 1.2 s while blocked (the moment somebody is waiting for the block
 * to lift after joining a Wi-Fi). FAILS OPEN: any exception reads as "not blocking", so a broken probe can never hide the pictures
 * of a phone that is at home.
 *
 * UNVERIFIED ON A PHONE (no device here): that `NET_CAPABILITY_NOT_ROAMING`, `isNetworkRoaming` and `getNetworkCountryIso` report as
 * the intercom app saw them on its phone (it confirmed the country branch and "foreign Wi-Fi is never blocked" on hardware; the two
 * roaming-flag tests never met a genuinely roaming network).
 *
 * @param atHomeLink the link to Home Assistant is up over the direct path (the LAN); with a Wi-Fi network present that proves home.
 */
class AndroidRoaming(
    private val app: Context,
    prefs: SharedPreferences,
    private val atHomeLink: () -> Boolean,
    journal: (String) -> Unit,
) {
    val gate = RoamingGate(PrefsHomeCountry(prefs), journal)

    @Volatile private var checkedAt = 0L

    /** True when pictures must be held back now. */
    fun blocking(): Boolean {
        refresh()
        return gate.blocking()
    }

    /**
     * 0.6.58: the class of the phone's connection (Wi-Fi, mobile, roaming) for Settings' per-network rows, from the same facts and the same
     * cache as [blocking]. "No data connection right now" keeps the last answer; the first answer, and any failure, is Mobile.
     */
    fun netClass(): NetClass {
        refresh()
        return lastClass
    }

    /** The viewer tapped "show" on this trip (the roaming hold is lifted). */
    fun tripAllowed(): Boolean = gate.tripAllowed()

    @Volatile private var lastClass = NetClass.MOBILE

    private fun refresh() {
        val now = SystemClock.elapsedRealtime()
        val ttl = if (gate.blocking()) TTL_BLOCKED_MS else TTL_MS
        if (checkedAt == 0L || now - checkedAt >= ttl) {
            checkedAt = now
            try {
                val facts = read()
                val verdict = gate.update(facts)
                NetworkPolicy.classify(facts, verdict.away)?.let { lastClass = it }
            } catch (_: Exception) {
                // fail open: the gate keeps its last verdict
            }
        }
    }

    /**
     * The ring pre-warm's question: is the phone's DEFAULT network Wi-Fi/Ethernet (nothing metered), mobile, or not knowable. Read fresh
     * (a ring is rare), never cached, and any exception is UNKNOWN (the pre-warm then does nothing).
     */
    fun defaultNet(): com.meda.portico.core.live.DefaultNet = try {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val def = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        when {
            def == null -> com.meda.portico.core.live.DefaultNet.UNKNOWN
            def.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && !def.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !def.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) &&
                !def.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> com.meda.portico.core.live.DefaultNet.UNKNOWN
            def.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || def.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> com.meda.portico.core.live.DefaultNet.WIFI
            def.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> com.meda.portico.core.live.DefaultNet.MOBILE
            else -> com.meda.portico.core.live.DefaultNet.UNKNOWN
        }
    } catch (_: Exception) {
        com.meda.portico.core.live.DefaultNet.UNKNOWN
    }

    /** The viewer tapped "Roaming. Tap to show". Lasts for the trip. */
    fun allowTrip() {
        gate.allowTrip()
        checkedAt = 0L
    }

    /** Forget the cache (a network callback saw a change). */
    fun invalidate() {
        checkedAt = 0L
    }

    @Suppress("DEPRECATION") // getAllNetworks: the only call that lists every network (with the WAN down the house Wi-Fi is demoted)
    private fun read(): RoamingFacts {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: throw IllegalStateException("no ConnectivityManager")
        val tm = app.getSystemService(TelephonyManager::class.java)
        val def = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val lanExists = cm.allNetworks.any {
            val c = cm.getNetworkCapabilities(it)
            c != null && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
        val wifiOrEth = def != null && (def.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || def.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        val cellular = def != null && def.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !wifiOrEth
        return RoamingFacts(
            lanVerified = atHomeLink() && lanExists,
            haveDefaultNet = def != null,
            wifiOrEthernet = wifiOrEth,
            cellular = cellular,
            notRoamingCap = def?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) ?: true,
            telephonyRoaming = try { tm?.isNetworkRoaming == true } catch (_: Exception) { false },
            hereIso = try { tm?.networkCountryIso.orEmpty() } catch (_: Exception) { "" },
            wifiJoinedNotDefault = lanExists && cellular,
        )
    }

    private class PrefsHomeCountry(private val prefs: SharedPreferences) : HomeCountryStore {
        override fun get(): String = prefs.getString(KEY, "").orEmpty()
        override fun set(iso: String) {
            prefs.edit().putString(KEY, iso).apply()
        }
    }

    private companion object {
        const val TTL_MS = 5_000L
        const val TTL_BLOCKED_MS = 1_200L
        const val KEY = "home_country"
    }
}
