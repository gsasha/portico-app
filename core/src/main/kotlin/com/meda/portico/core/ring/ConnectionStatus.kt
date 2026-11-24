package com.meda.portico.core.ring

import com.meda.portico.core.ha.LinkPath

/** The colour of the status dot. The colour carries HEALTH; the label carries the ROUTE (or the reason). */
enum class Health { OK, WARN, BAD }

/** The one short word under the status dot. The app maps each to a string resource. */
enum class StatusLabel {
    /** Connected over `ha_url` directly (the LAN, or whatever it resolves to). */
    DIRECT,

    /** Connected over the tailnet. */
    TAILNET,

    /** Connected, the route not known (a socket opened before the path was recorded). */
    CONNECTED,
    CONNECTING,

    /** Not connected and not about to stop: it is retrying. */
    OFFLINE,
    NOT_SET_UP,
    TOKEN_REFUSED,

    /** DEV-544: HA no longer knows this phone's login (removed or signed out): enrol it again. */
    SIGNED_OUT,
    ADDRESS_REFUSED,

    /** Paused on purpose: nothing is down. */
    PAUSED,

    /** The link is down and the app is trying again ([Reconnecting]): shown as "HA reconnecting". */
    RECONNECTING,
}

/**
 * the owner's wording (D-20261118-e0b1): while the link to Home Assistant is down and the app is trying to bring it back, the screens say
 * "HA reconnecting" and nothing else. Not set up, token refused, signed out, address refused and paused are NOT reconnecting and keep their words.
 */
object Reconnecting {
    /** The words, for tests; the app's string resource carries the same text. */
    const val TEXT = "HA reconnecting"

    /**
     * True while the socket is waiting out a backoff ([LinkState.WAITING_TO_RETRY]), or a connect is in progress after the link was
     * CONNECTED once in this process ([wasConnected]); the very first connect of a launch keeps "connecting".
     */
    fun now(link: LinkState, wasConnected: Boolean): Boolean =
        link == LinkState.WAITING_TO_RETRY || (link == LinkState.CONNECTING && wasConnected)
}

/** [glyph]: a filled dot only when Home Assistant is answering; a hollow one otherwise; a pause sign while paused. */
data class ConnectionStatus(val health: Health, val label: StatusLabel) {
    val glyph: String get() = if (label == StatusLabel.PAUSED) "⏸" else if (health == Health.OK) "●" else "○"
}

/**
 * Pure: the socket's state (and the path it took) -> the status shown in Home's top bar. Mirrors the
 * intercom app's bar, whose rules were learned the hard way:
 *
 * - **Green means Home Assistant is ANSWERING**, not that a route exists. [LinkState.CONNECTED] is set only
 *   once the socket has authenticated and `portico/version` has answered contract 3, so it is the right test.
 * - **The route is a label, not a colour.** Away from home the tailnet is the intended path and works
 *   perfectly, so a connected tailnet is as green as a direct connection ("why is the tailscale icon
 *   orange?" was the intercom app's bug).
 * - **Paused is not a fault.** Its own sign and word, amber, never the red of "offline".
 * - **Amber is for "not yet"** (connecting); red is for "not connected and nothing in flight" and for the
 *   states that will not heal by themselves (not set up, token refused, address refused).
 */
object ConnectionModel {
    fun of(link: LinkState, path: LinkPath?, wasConnected: Boolean = false): ConnectionStatus = when {
        Reconnecting.now(link, wasConnected) -> ConnectionStatus(if (link == LinkState.CONNECTING) Health.WARN else Health.BAD, StatusLabel.RECONNECTING)
        else -> byState(link, path)
    }

    private fun byState(link: LinkState, path: LinkPath?): ConnectionStatus = when (link) {
        LinkState.PAUSED -> ConnectionStatus(Health.WARN, StatusLabel.PAUSED)
        LinkState.CONNECTED -> ConnectionStatus(
            Health.OK,
            when (path) {
                LinkPath.DIRECT -> StatusLabel.DIRECT
                LinkPath.TAILNET -> StatusLabel.TAILNET
                null -> StatusLabel.CONNECTED
            },
        )
        LinkState.CONNECTING -> ConnectionStatus(Health.WARN, StatusLabel.CONNECTING)
        LinkState.WAITING_TO_RETRY, LinkState.STOPPED -> ConnectionStatus(Health.BAD, StatusLabel.OFFLINE)
        LinkState.NOT_CONFIGURED -> ConnectionStatus(Health.BAD, StatusLabel.NOT_SET_UP)
        LinkState.AUTH_REJECTED -> ConnectionStatus(Health.BAD, StatusLabel.TOKEN_REFUSED)
        LinkState.SIGNED_OUT -> ConnectionStatus(Health.BAD, StatusLabel.SIGNED_OUT)
        LinkState.URL_REFUSED -> ConnectionStatus(Health.BAD, StatusLabel.ADDRESS_REFUSED)
    }
}
