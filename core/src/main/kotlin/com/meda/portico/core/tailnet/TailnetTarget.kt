package com.meda.portico.core.tailnet

import com.meda.portico.core.net.Ipv4
import com.meda.portico.core.net.Ipv6
import com.meda.portico.core.net.UrlCheck
import com.meda.portico.core.net.UrlPolicy
import java.net.URI

/**
 * Where the tailnet path dials (DEV-557, docs/tailnet.md "Dial target").
 *
 * The URL the app opens is always `ha_url`, unchanged: OkHttp does TLS (SNI, certificate,
 * hostname) for `ha_url`'s host. Only the TCP connection is redirected, through the local
 * forwarder, to a target on the tailnet. Away from home `ha_url`'s internal name does not
 * resolve, and tsnet's own dialer resolves non-MagicDNS names with the PUBLIC resolver (it does
 * not use the tailnet's split DNS), so the target must be an address: the enrollment's optional
 * `ha_dial` (e.g. `"192.168.1.5:443"`), else `ha_url`'s own host and port.
 */
object TailnetTarget {

    /**
     * Validates an `ha_dial` value: `a.b.c.d:port` or `[v6]:port`, an IP LITERAL (no name: a name
     * needs the DNS that is missing away from home) that [UrlPolicy] classifies as private, and
     * not loopback or link-local (neither means anything across a tailnet; 127.x would point the
     * forwarder at the phone itself). Allowed: 10/8, 172.16/12, 192.168/16, 100.64/10 (CGNAT,
     * the tailnet's own range), fc00::/7 (ULA, which holds the tailnet's IPv6 range).
     * Returns the canonical `host:port` (IPv6 bracketed, lower-case), or null.
     */
    fun parseHaDial(text: String?): String? {
        val t = text?.trim() ?: return null
        if (t.isEmpty() || t.length > 64) return null
        val check = UrlPolicy.check("http://$t")
        if (check !is UrlCheck.HttpPrivate) return null
        // UrlPolicy accepted it as an authority; now insist on literal-ip:port and nothing else.
        val hostPart: String
        val port: String
        if (t.startsWith("[")) {
            val close = t.indexOf(']')
            if (close < 0 || close + 1 >= t.length || t[close + 1] != ':') return null
            hostPart = t.substring(1, close).lowercase()
            port = t.substring(close + 2)
            val b = Ipv6.parse(hostPart) ?: return null
            if (!Ipv6.isPrivate(b) || isV6LoopbackOrLinkLocal(b)) return null
            if (isV4Mapped(b)) return null // write the IPv4 form instead
        } else {
            val colon = t.lastIndexOf(':')
            if (colon <= 0 || t.indexOf(':') != colon) return null
            hostPart = t.substring(0, colon)
            port = t.substring(colon + 1)
            val ip = Ipv4.parseCanonical(hostPart) ?: return null
            if (!Ipv4.isPrivate(ip) || isV4LoopbackOrLinkLocal(ip)) return null
        }
        if (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' } || port[0] == '0') return null
        if (port.toInt() !in 1..65535) return null
        return if (t.startsWith("[")) "[$hostPart]:$port" else "$hostPart:$port"
    }

    /**
     * The forwarder's target: a stored (already validated) [haDial], else `ha_url`'s host and port
     * (443 for https, 80 for http). Null when there is no usable URL.
     */
    fun resolve(haUrl: String?, haDial: String?): String? {
        parseHaDial(haDial)?.let { return it }
        val u = haUrl?.trim() ?: return null
        if (UrlPolicy.check(u) is UrlCheck.Refused) return null
        val uri = try {
            URI(u)
        } catch (_: Exception) {
            return null
        }
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        val port = when {
            uri.port in 1..65535 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            uri.scheme.equals("http", ignoreCase = true) -> 80
            else -> return null
        }
        val h = host.removePrefix("[").removeSuffix("]")
        return if (':' in h) "[$h]:$port" else "$h:$port"
    }

    /**
     * The IP of a `host:port` target ([resolve]'s output) when its host is an IP literal
     * (`192.168.1.5`, or the v6 address without brackets); null when it is a name.
     */
    fun ipOf(target: String?): String? {
        val t = target?.trim() ?: return null
        if (t.startsWith("[")) {
            val close = t.indexOf(']')
            if (close < 0) return null
            val h = t.substring(1, close).lowercase()
            return if (Ipv6.parse(h) != null) h else null
        }
        val colon = t.lastIndexOf(':')
        if (colon <= 0 || t.indexOf(':') != colon) return null
        val h = t.substring(0, colon)
        return if (Ipv4.parseCanonical(h) != null) h else null
    }

    private fun isV4LoopbackOrLinkLocal(ip: Long): Boolean {
        val a = (ip shr 24).toInt()
        val b = ((ip shr 16) and 0xFF).toInt()
        return a == 127 || (a == 169 && b == 254)
    }

    private fun isV4Mapped(b: IntArray): Boolean = (0 until 10).all { b[it] == 0 } && b[10] == 0xFF && b[11] == 0xFF

    private fun isV6LoopbackOrLinkLocal(b: IntArray): Boolean {
        val loopback = (0 until 15).all { b[it] == 0 } && b[15] == 1
        val linkLocal = b[0] == 0xFE && (b[1] and 0xC0) == 0x80
        return loopback || linkLocal
    }
}
