package com.meda.portico.home

import com.meda.portico.AppGraph
import com.meda.portico.core.ring.LinkState

/** The signalling client's state as `:core`'s [LinkState] (the screens' models take that one); an unknown name is [LinkState.STOPPED]. */
/** the owner's "HA reconnecting": the link is down and the app is bringing it back ([com.meda.portico.core.ring.Reconnecting]). */
fun AppGraph.reconnectingNow(): Boolean = com.meda.portico.core.ring.Reconnecting.now(currentLinkState(), signaling.everConnected)

fun AppGraph.currentLinkState(): LinkState = try {
    LinkState.valueOf(signaling.state.name)
} catch (_: IllegalArgumentException) {
    LinkState.STOPPED
}
