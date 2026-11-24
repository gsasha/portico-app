package com.meda.portico.core.net

/**
 * Why [UrlPolicy] refused a URL. The message is for Setup and never quotes the input (a URL can
 * carry a user name, a password or a token).
 */
enum class UrlRefusal(val message: String) {
    EMPTY("The address is empty."),
    NOT_HTTP("The address does not start with https:// or http://."),
    MALFORMED("The address contains spaces, control characters or a backslash."),
    USERINFO("The address contains a user name or password (before an @). Remove it."),
    EMPTY_HOST("The address has no host name."),
    BAD_PORT("The address has an invalid port, or an IPv6 address without [brackets]."),
    NON_CANONICAL_IPV4(
        "The address is an IP address in an unusual form (leading zeros, hex, or fewer than four " +
            "parts). Write it as four plain numbers, like 192.168.1.10.",
    ),
    BAD_IPV6("The address has an IPv6 address Portico cannot read (zone ids such as %wlan0 are not supported)."),
    BAD_HOSTNAME("The address has a host name with characters outside a-z, 0-9, '-' and '.'."),
    HTTP_PUBLIC_HOST(
        "Plain http (unencrypted) is allowed only to an address on your own network (a private IP " +
            "address, or a name ending in .local, .lan, .home.arpa, .internal or with no dots). " +
            "Use an https address for this one.",
    ),
}

/** [UrlPolicy.check]'s verdict. [host] is lower-case, without brackets or a trailing dot. */
sealed interface UrlCheck {
    /** `https://`: always allowed. */
    data class Https(val host: String) : UrlCheck

    /** `http://` to a private/LAN host: allowed, and Setup shows a persistent warning. */
    data class HttpPrivate(val host: String) : UrlCheck

    data class Refused(val reason: UrlRefusal) : UrlCheck
}

/**
 * The app's network URL policy (PR-J; the owner: "on the basis of whether https is configured").
 *
 * - `https://` is always allowed.
 * - `http://` is allowed only to a private/LAN host: IPv4 10/8, 172.16/12, 192.168/16,
 *   169.254/16 (link-local), 100.64/10 (CGNAT, which covers a tailnet), 127/8; IPv6 fc00::/7
 *   (ULA), fe80::/10 (link-local), ::1, and IPv4-mapped (::ffff:a.b.c.d) forms of the IPv4
 *   ranges; host names ending in `.local`, `.lan`, `.home.arpa`, `.internal` or `.localdomain`;
 *   and single-label names (`homeassistant`, `localhost`), which only a local search domain or
 *   hosts file can resolve. A single-label name WITH a trailing dot (`homeassistant.`) is
 *   absolute, skips the search domain and resolves in public DNS, so it is not private.
 * - Everything else over http is refused, as are other schemes, user-info, empty hosts and any
 *   address form that different parsers read differently: IPv4 must be a canonical dotted quad
 *   (no `010.0.0.1`, `0x7f.1`, `127.1`, `2130706433`), so the host we classify is the host the
 *   connection goes to.
 *
 * Android's platform policy allows cleartext for the whole app (`network_security_config.xml`);
 * THIS function is the real restriction, and every connect path must call it (docs/reference/network-policy.md). Pure, total, never throws.
 */
object UrlPolicy {

    private val PRIVATE_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal", ".localdomain")

    fun check(url: String?): UrlCheck = try {
        checkOrRefuse(url)
    } catch (_: RuntimeException) {
        UrlCheck.Refused(UrlRefusal.MALFORMED) // defensive: no path above is expected to throw
    }

    /** The WebSocket form of a URL (`ws://` as `http://`, `wss://` as `https://`); http(s) as [check]. */
    fun checkWebSocket(url: String?): UrlCheck {
        val u = url?.trim() ?: return check(null)
        val mapped = when {
            u.startsWith("wss://", ignoreCase = true) -> "https://" + u.substring(6)
            u.startsWith("ws://", ignoreCase = true) -> "http://" + u.substring(5)
            else -> u
        }
        return check(mapped)
    }

    private fun refuse(r: UrlRefusal) = UrlCheck.Refused(r)

    private fun checkOrRefuse(raw: String?): UrlCheck {
        val url = raw?.trim() ?: return refuse(UrlRefusal.EMPTY)
        if (url.isEmpty()) return refuse(UrlRefusal.EMPTY)

        val sep = url.indexOf("://")
        if (sep <= 0) return refuse(UrlRefusal.NOT_HTTP)
        val https = when (url.substring(0, sep).lowercase()) {
            "https" -> true
            "http" -> false
            else -> return refuse(UrlRefusal.NOT_HTTP)
        }
        if (url.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return refuse(UrlRefusal.MALFORMED)

        val rest = url.substring(sep + 3)
        val end = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, end)
        if ('@' in authority) return refuse(UrlRefusal.USERINFO)

        val hostPart: String
        val port: String?
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return refuse(UrlRefusal.BAD_IPV6)
            hostPart = authority.substring(0, close + 1)
            val after = authority.substring(close + 1)
            port = when {
                after.isEmpty() -> null
                after.startsWith(":") -> after.substring(1)
                else -> return refuse(UrlRefusal.BAD_PORT)
            }
        } else {
            val colons = authority.count { it == ':' }
            if (colons > 1) return refuse(UrlRefusal.BAD_PORT)
            hostPart = authority.substringBefore(':')
            port = if (colons == 1) authority.substringAfter(':') else null
        }
        if (hostPart.isEmpty()) return refuse(UrlRefusal.EMPTY_HOST)
        if (port != null && !validPort(port)) return refuse(UrlRefusal.BAD_PORT)

        return if (hostPart.startsWith("[")) {
            val literal = hostPart.substring(1, hostPart.length - 1).lowercase()
            val bytes = Ipv6.parse(literal) ?: return refuse(UrlRefusal.BAD_IPV6)
            verdict(https, literal, Ipv6.isPrivate(bytes))
        } else {
            classifyName(https, hostPart.lowercase())
        }
    }

    private fun validPort(p: String): Boolean =
        p.length in 1..5 && p.all { it in '0'..'9' } && p.toInt() in 1..65535

    private fun verdict(https: Boolean, host: String, private: Boolean): UrlCheck = when {
        https -> UrlCheck.Https(host)
        private -> UrlCheck.HttpPrivate(host)
        else -> refuse(UrlRefusal.HTTP_PUBLIC_HOST)
    }

    /** A host that is not a bracketed IPv6 literal: a dotted-quad IPv4 or a DNS name. */
    private fun classifyName(https: Boolean, host: String): UrlCheck {
        val trailingDot = host.endsWith(".")
        val name = if (trailingDot) host.dropLast(1) else host
        val labels = name.split('.')
        if (name.isEmpty() || labels.any { it.isEmpty() }) return refuse(UrlRefusal.BAD_HOSTNAME)

        // WHATWG URL parsers (and inet_aton) read a host whose LAST label is numeric as IPv4, in
        // forms other parsers read as names. Accept only the one form everyone agrees on.
        if (looksNumeric(labels.last())) {
            if (trailingDot) return refuse(UrlRefusal.NON_CANONICAL_IPV4)
            val ip = Ipv4.parseCanonical(labels) ?: return refuse(UrlRefusal.NON_CANONICAL_IPV4)
            return verdict(https, name, Ipv4.isPrivate(ip))
        }

        if (name.length > 253 || !labels.all(::validLabel)) return refuse(UrlRefusal.BAD_HOSTNAME)
        val private = (labels.size == 1 && !trailingDot) ||
            PRIVATE_SUFFIXES.any { name.endsWith(it) && name.length > it.length }
        return verdict(https, name, private)
    }

    /** Digits only, or a `0x` hex form: what an IPv4 parser would claim. */
    private fun looksNumeric(label: String): Boolean =
        label.all { it in '0'..'9' } ||
            (label.startsWith("0x") && label.drop(2).all { it in '0'..'9' || it in 'a'..'f' })

    private fun validLabel(l: String): Boolean =
        l.length in 1..63 && l.first() != '-' && l.last() != '-' &&
            l.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
}

internal object Ipv4 {
    /** Exactly four decimal parts 0..255 without leading zeros, as a 32-bit value; else null. */
    fun parseCanonical(parts: List<String>): Long? {
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            if (p.length > 1 && p[0] == '0') return null
            val n = p.toInt()
            if (n > 255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    fun parseCanonical(text: String): Long? = parseCanonical(text.split('.'))

    private fun inNet(ip: Long, net: Long, bits: Int): Boolean {
        val mask = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
        return (ip and mask) == (net and mask)
    }

    private fun net(a: Int, b: Int, c: Int, d: Int) = (a.toLong() shl 24) or (b.toLong() shl 16) or (c.toLong() shl 8) or d.toLong()

    private val PRIVATE = listOf(
        net(10, 0, 0, 0) to 8,
        net(172, 16, 0, 0) to 12,
        net(192, 168, 0, 0) to 16,
        net(169, 254, 0, 0) to 16,
        net(100, 64, 0, 0) to 10,
        net(127, 0, 0, 0) to 8,
    )

    fun isPrivate(ip: Long): Boolean = PRIVATE.any { (n, bits) -> inNet(ip, n, bits) }
}

internal object Ipv6 {
    /**
     * An RFC 4291 text address (no zone id, no brackets) as 16 bytes, or null. A trailing dotted
     * quad must be canonical, as for plain IPv4.
     */
    fun parse(text: String): IntArray? {
        if (text.isEmpty() || text.any { !(it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.') }) return null
        var body = text
        var tail: IntArray? = null
        if ('.' in body) {
            val lastColon = body.lastIndexOf(':')
            if (lastColon < 0) return null
            val v4 = Ipv4.parseCanonical(body.substring(lastColon + 1)) ?: return null
            tail = intArrayOf(((v4 shr 16) and 0xFFFF).toInt(), (v4 and 0xFFFF).toInt())
            // Keep the "::" when the dotted quad directly follows it.
            body = if (body.substring(0, lastColon).endsWith(":")) body.substring(0, lastColon + 1) else body.substring(0, lastColon)
        }
        val groupsWanted = 8 - (tail?.size ?: 0)
        val dbl = body.indexOf("::")
        if (dbl >= 0 && body.indexOf("::", dbl + 1) >= 0) return null
        val groups: List<Int>
        if (dbl >= 0) {
            val head = parseGroups(body.substring(0, dbl)) ?: return null
            val back = parseGroups(body.substring(dbl + 2)) ?: return null
            val zeros = groupsWanted - head.size - back.size
            if (zeros < 1) return null
            groups = head + List(zeros) { 0 } + back
        } else {
            groups = parseGroups(body) ?: return null
            if (groups.size != groupsWanted) return null
        }
        val all = groups + (tail?.toList() ?: emptyList())
        if (all.size != 8) return null
        val out = IntArray(16)
        for ((i, g) in all.withIndex()) {
            out[2 * i] = g shr 8
            out[2 * i + 1] = g and 0xFF
        }
        return out
    }

    /** Colon-separated 1-4 hex digit groups; "" is no groups. */
    private fun parseGroups(s: String): List<Int>? {
        if (s.isEmpty()) return emptyList()
        return s.split(':').map { g ->
            if (g.isEmpty() || g.length > 4 || '.' in g) return null
            g.toInt(16)
        }
    }

    fun isPrivate(b: IntArray): Boolean {
        val loopback = (0 until 15).all { b[it] == 0 } && b[15] == 1
        val ula = (b[0] and 0xFE) == 0xFC // fc00::/7
        val linkLocal = b[0] == 0xFE && (b[1] and 0xC0) == 0x80 // fe80::/10
        val mapped = (0 until 10).all { b[it] == 0 } && b[10] == 0xFF && b[11] == 0xFF
        if (mapped) {
            val v4 = (b[12].toLong() shl 24) or (b[13].toLong() shl 16) or (b[14].toLong() shl 8) or b[15].toLong()
            return Ipv4.isPrivate(v4)
        }
        return loopback || ula || linkLocal
    }
}
