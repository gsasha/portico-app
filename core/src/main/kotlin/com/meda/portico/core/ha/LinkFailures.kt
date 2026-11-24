package com.meda.portico.core.ha

import com.meda.portico.core.tailnet.ForwarderDial

/**
 * Why one connection attempt to Home Assistant did not open (Setup's per-path line). Each maps
 * to one plain-words reason in the app's strings. The classes are what the user (or a
 * screenshot) needs to tell the causes apart, not the exception names.
 */
enum class FailureClass {
    /** The host name did not resolve (away from home: an internal name; on the tailnet: no `ha_dial`). */
    NAME_NOT_RESOLVED,

    /** The target answered with a reset: nothing listens on that port (or a firewall rejects). */
    REFUSED,

    /** No answer in time (the direct path's own deadline, or the OS connect timeout). */
    TIMED_OUT,

    /** The OS has no route: "network is unreachable", "no route to host". */
    UNREACHABLE,

    /** The TLS handshake failed for another reason (protocol, cipher, the peer hung up mid-handshake). */
    TLS,

    /** The server's certificate is not trusted (unknown CA, expired, self-signed). */
    CERTIFICATE,

    /** The certificate does not match the address's host name. */
    HOSTNAME,

    /** The server answered the WebSocket upgrade with an HTTP status ([LinkFailure.httpStatus]). */
    HTTP_STATUS,

    /** Home Assistant opened the socket and refused this phone's access token. */
    AUTH_REJECTED,

    /**
     * Home Assistant opened the socket and answered, but not with contract 3 ([LinkFailure.serverContract], when it named
     * one). Retrying: unlike [AUTH_REJECTED] this heals by itself when the server is updated.
     */
    CONTRACT_MISMATCH,

    /** The connection was closed before the WebSocket opened, with no other clue. */
    CLOSED_EARLY,

    /** Tailnet path only: the node did not come up (see the tailnet line for why). */
    TAILNET_NOT_UP,

    /** Tailnet path only: the forwarder's dial found no route ("no route to host"). */
    NO_ROUTE,

    /** Tailnet path only: the forwarder's dial got no answer: a policy rule drops it, or no peer routes it. */
    DIAL_TIMEOUT,

    /** Tailnet path only: the local forwarder refused the connection's token. */
    FORWARDER_TOKEN,

    /** Tailnet path only: nothing listened on the local forwarder's port ([LinkFailure.endpoint] says which, when known). */
    FORWARDER_DOWN,

    /** Not a failure: the other path opened first, so this one was not needed (DEV-694: both are started at once on mobile data). */
    SUPERSEDED,

    /** Anything else. */
    OTHER,
}

/**
 * One classified failure. [httpStatus] only for [FailureClass.HTTP_STATUS]; [endpoint] only for
 * [FailureClass.FORWARDER_DOWN], the local forwarder's `127.0.0.1:<port>` that refused; [serverContract] only for
 * [FailureClass.CONTRACT_MISMATCH]. Holds no URL, token or message.
 */
data class LinkFailure(val cls: FailureClass, val httpStatus: Int? = null, val endpoint: String? = null, val serverContract: Int? = null)

/** One link of a throwable's cause chain: the class's simple name and its message (read, never kept). */
data class ThrowableInfo(val className: String, val message: String?)

/**
 * The classification table (JVM-tested branch by branch). Inputs are the exception chain's class
 * names and messages (OkHttp, the JDK, Android's libcore) and the Go forwarder's dial kind; the
 * output never carries a message, so nothing secret can leak into Setup through it.
 */
object LinkFailures {

    /** The direct path did not open within FallbackConnector's deadline. */
    val DIRECT_DEADLINE = LinkFailure(FailureClass.TIMED_OUT)

    /** From a throwable's chain (outermost first) and the HTTP response's code if there was one. */
    fun classify(chain: List<ThrowableInfo>, httpStatus: Int? = null): LinkFailure {
        if (httpStatus != null && httpStatus != 101) return LinkFailure(FailureClass.HTTP_STATUS, httpStatus)
        val names = chain.map { it.className }
        val text = chain.joinToString(" | ") { it.message.orEmpty() }.lowercase()
        fun has(vararg n: String) = n.any { it in names }
        if (has("ForwarderConnectException")) {
            return LinkFailure(FailureClass.FORWARDER_DOWN, endpoint = FORWARDER_ENDPOINT.find(text)?.groupValues?.get(1))
        }
        val cls = when {
            has("TailnetUnavailableException") -> FailureClass.TAILNET_NOT_UP
            has("UnknownHostException") -> FailureClass.NAME_NOT_RESOLVED
            has("SSLPeerUnverifiedException") -> FailureClass.HOSTNAME
            has("CertificateException", "CertPathValidatorException", "CertificateExpiredException",
                "CertificateNotYetValidException", "SunCertPathBuilderException", "CertPathBuilderException") ->
                FailureClass.CERTIFICATE
            has("SSLHandshakeException", "SSLException", "SSLProtocolException") ->
                if ("trust anchor" in text || "pkix" in text || "certificate" in text) FailureClass.CERTIFICATE else FailureClass.TLS
            has("NoRouteToHostException") -> FailureClass.UNREACHABLE
            "econnrefused" in text || "connection refused" in text -> FailureClass.REFUSED
            "enetunreach" in text || "ehostunreach" in text || "network is unreachable" in text ||
                "no route to host" in text -> FailureClass.UNREACHABLE
            has("SocketTimeoutException") || "timed out" in text || "timeout" in text -> FailureClass.TIMED_OUT
            has("ConnectException") -> FailureClass.REFUSED // the JDK's ConnectException is "Connection refused"
            has("ProtocolException") -> httpFromProtocol(text)
            has("EOFException") || "unexpected end of stream" in text || "connection reset" in text ||
                "closed by peer" in text -> FailureClass.CLOSED_EARLY
            else -> FailureClass.OTHER
        }
        if (cls == FailureClass.HTTP_STATUS) return LinkFailure(cls, PROTOCOL_STATUS.find(text)?.groupValues?.get(1)?.toIntOrNull())
        return LinkFailure(cls)
    }

    /** ForwarderConnectException's fixed message; only a loopback address and a port ever match. */
    private val FORWARDER_ENDPOINT = Regex("""local forwarder (127\.0\.0\.1:\d{1,5}) not listening""")

    /** OkHttp: "Expected HTTP 101 response but was '404 Not Found'". */
    private val PROTOCOL_STATUS = Regex("""but was '(\d{3})""")

    private fun httpFromProtocol(text: String): FailureClass =
        if (PROTOCOL_STATUS.containsMatchIn(text)) FailureClass.HTTP_STATUS else FailureClass.OTHER

    /** The Go forwarder's dial kind -> the tailnet path's class; null for `ok` (the dial worked). */
    fun fromGoDial(kind: String): FailureClass? = when (kind) {
        "ok" -> null
        "no_such_host" -> FailureClass.NAME_NOT_RESOLVED
        "timeout" -> FailureClass.DIAL_TIMEOUT
        "refused" -> FailureClass.REFUSED
        "unreachable" -> FailureClass.NO_ROUTE
        "token" -> FailureClass.FORWARDER_TOKEN
        else -> FailureClass.OTHER // "error", or a kind a newer library added
    }

    /**
     * The tailnet path's class. OkHttp only ever talks to the local forwarder, so on its own it
     * sees a dial failure as a bare end of stream: the forwarder's record of a dial at or after
     * [attemptStartMs] says what really happened. A dial that worked leaves OkHttp's class (the
     * failure is then TLS or HTTP, end to end with HA). A refused connect can only be the local
     * forwarder's port, never HA's.
     */
    fun tailnet(okhttp: LinkFailure, dial: ForwarderDial?, attemptStartMs: Long): LinkFailure {
        // Not up, or the forwarder itself refused: no dial of this attempt reached Go, so a
        // record from another connection must not explain it.
        if (okhttp.cls == FailureClass.TAILNET_NOT_UP || okhttp.cls == FailureClass.FORWARDER_DOWN) return okhttp
        if (dial != null && dial.atMs >= attemptStartMs - CLOCK_SLACK_MS) {
            fromGoDial(dial.kind)?.let { return LinkFailure(it) }
        }
        return when (okhttp.cls) {
            FailureClass.REFUSED -> LinkFailure(FailureClass.FORWARDER_DOWN)
            else -> okhttp
        }
    }

    /** The Go and Kotlin clocks are the same wall clock; this only absorbs millisecond rounding. */
    const val CLOCK_SLACK_MS = 1_000L
}

/** One attempt on one path: [failure] null means it opened. [atMs] is wall-clock time. */
data class PathAttempt(val path: LinkPath, val atMs: Long, val failure: LinkFailure?)

/**
 * The last attempt on each path, for Setup. Written by FallbackConnector's callbacks and the
 * signaling client (auth rejected), read by Setup's UI thread. Thread-safe.
 */
class LinkDiagnostics {
    private val lock = Any()
    private var direct: PathAttempt? = null
    private var tailnet: PathAttempt? = null
    private var lastOpened: LinkPath? = null

    fun record(a: PathAttempt) = synchronized(lock) {
        when (a.path) {
            LinkPath.DIRECT -> direct = a
            LinkPath.TAILNET -> tailnet = a
        }
        if (a.failure == null) lastOpened = a.path
    }

    /** HA refused the token on the socket that opened last: that path's line says so. */
    fun authRejected(atMs: Long) = synchronized(lock) {
        val p = lastOpened ?: return@synchronized
        record(PathAttempt(p, atMs, LinkFailure(FailureClass.AUTH_REJECTED)))
        lastOpened = p
    }

    /**
     * `portico/version` did not answer contract 3 on the socket that opened last: that path's line says so, and keeps
     * saying it until the next attempt on the path (a retry that finds contract 3 records an opened attempt).
     */
    fun contractMismatch(atMs: Long, serverContract: Int?) = synchronized(lock) {
        val p = lastOpened ?: return@synchronized
        record(PathAttempt(p, atMs, LinkFailure(FailureClass.CONTRACT_MISMATCH, serverContract = serverContract)))
        lastOpened = p
    }

    fun last(path: LinkPath): PathAttempt? = synchronized(lock) {
        if (path == LinkPath.DIRECT) direct else tailnet
    }
}

/** "just now", "2 s ago", "4 min ago", "3 h ago", "2 d ago". Plain English, like Setup's other lines. */
object Ago {
    fun text(nowMs: Long, atMs: Long): String {
        val s = (nowMs - atMs) / 1000
        return when {
            s < 1 -> "just now"
            s < 60 -> "$s s ago"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }
}
