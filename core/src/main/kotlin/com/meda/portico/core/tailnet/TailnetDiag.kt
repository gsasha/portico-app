package com.meda.portico.core.tailnet

/**
 * The tailnet connector could not get a forwarder: the node is not up (or not built). Its
 * message names the state, never a key. `LinkFailures` maps it to `TAILNET_NOT_UP`.
 */
class TailnetUnavailableException(why: String) : IllegalStateException("tailnet not up ($why)")

/**
 * The tailnet connector's socket could not connect to the local forwarder at [endpoint]
 * (`127.0.0.1:<port>`, [Forwarder.endpointOf]). Its message is fixed: `local forwarder <endpoint>
 * not listening`, so `LinkFailures` can show the endpoint (never a token or URL) as
 * `FORWARDER_DOWN`. A `ConnectException`, so OkHttp treats it like any refused connect.
 */
class ForwarderConnectException(val endpoint: String, cause: Throwable? = null) :
    java.net.ConnectException("local forwarder $endpoint not listening") {
    init {
        if (cause != null) initCause(cause)
    }
}

/**
 * The Go forwarder's last dial through the tailnet (`gocore/tailnet.ForwarderStatus()`):
 * `none` | `<kind>\t<unix ms>[\t<short cause>]`. [kind] is one of ok, no_such_host, timeout,
 * refused, unreachable, token, error (or a newer one). [detail] is the innermost Go error, which
 * names addresses only; the Go side masks the token and any key.
 */
data class ForwarderDial(val kind: String, val atMs: Long, val detail: String = "") {
    companion object {
        /** Null for `none`, an unreadable line, or an older library without the call. Never throws. */
        fun parse(line: String?): ForwarderDial? {
            val f = (line ?: return null).trim().split('\t')
            if (f.size < 2 || f[0].isEmpty() || f[0] == "none") return null
            if (!f[0].all { it in 'a'..'z' || it == '_' }) return null
            val at = f[1].toLongOrNull() ?: return null
            return ForwarderDial(f[0], at, f.drop(2).joinToString(" ").take(MAX_DETAIL))
        }

        const val MAX_DETAIL = 200
    }
}

/**
 * Whether any tailnet peer offers a route to the dial IP (`gocore/tailnet.RouteCheck`), read
 * from the node's netmap: each peer's PrimaryRoutes, AllowedIPs and own addresses.
 */
sealed interface RouteCheck {
    /** [peer] routes [prefix], which contains the dial IP. */
    data class Via(val peer: String, val prefix: String) : RouteCheck

    /** No peer routes [ip]; [peersSeen] peers are in the netmap at all. */
    data class None(val ip: String, val peersSeen: Int) : RouteCheck

    /** The node is not up, or its peers are not known yet. */
    data object Unknown : RouteCheck

    /** The target is a host name, so there is nothing to look up. */
    data object NotIp : RouteCheck

    companion object {
        /** `via\t<peer>\t<prefix>` | `none\t<ip>\t<n>` | `unknown` | `not_ip`; anything else is [Unknown]. */
        fun parse(line: String?): RouteCheck {
            val f = (line ?: "").trim().split('\t')
            return when (f[0]) {
                "via" -> if (f.size >= 3 && f[1].isNotBlank()) Via(f[1].take(MAX_NAME), f[2].take(64)) else Unknown
                "none" -> {
                    val n = f.getOrNull(2)?.toIntOrNull()
                    if (f.size >= 3 && f[1].isNotBlank() && n != null) None(f[1].take(64), n) else Unknown
                }
                "not_ip" -> NotIp
                else -> Unknown
            }
        }

        const val MAX_NAME = 64
    }
}
