package com.meda.portico.core.connlog

import com.meda.portico.core.ha.NetSnapshot

/**
 * The connection log's view of the phone's networks (DEV-705): [NetSnapshot] (what `AndroidNetworkFacts` reads) to a
 * [NetState]. Pure, so the classification is tested on the JVM. [nid] is the app-local network counter (see [NetState]).
 */
object ConnNetMapper {
    /** A snapshot that could not be read maps to [NetClass.OTHER] (unknown), never to NONE: "no network" is a claim. */
    fun of(snapshot: NetSnapshot?, nid: Int): NetState {
        if (snapshot == null) return NetState(NetClass.OTHER, wifi = false, cell = false, vpn = false, validated = false, nid = nid)
        val d = snapshot.defaultNet
        val wifi = snapshot.networks.any { it.wifiOrEthernet && !it.vpn }
        val cell = snapshot.networks.any { it.cellular && !it.vpn }
        val vpn = snapshot.networks.any { it.vpn }
        val cls = when {
            d == null -> NetClass.NONE
            d.vpn -> NetClass.VPN
            d.wifiOrEthernet -> NetClass.WIFI
            d.cellular -> NetClass.CELLULAR
            else -> NetClass.OTHER
        }
        return NetState(cls, wifi, cell, vpn, d?.validated == true, nid)
    }
}
