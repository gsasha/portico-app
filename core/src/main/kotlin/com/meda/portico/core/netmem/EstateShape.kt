package com.meda.portico.core.netmem

/**
 * Does the phone's address look like the estate's? A PRIOR that moves a network the phone has
 * never seen between "start the tailnet now", "hedge" and "direct first"; it is never a verdict
 * (DEV-694 design section 10, the owner's decision D5). Pure, total, never throws.
 *
 * The list of home prefixes is data ([homePrefixes], CIDR strings, v4 or v6); the app ships none
 * ([DEFAULT_HOME_PREFIXES] is empty), so with no list a phone is never HOME and the answer rests on
 * the comparison with HA's own private block alone (OTHER / NOT_ESTATE).
 */
enum class Shape {
    /** Inside a home prefix: a phone on the IoT or guest VLAN is home-shaped too. */
    HOME,

    /** A different RFC 1918 block from HA's, or a public / CGNAT address: most foreign Wi-Fi. */
    NOT_ESTATE,

    /** Cannot tell: another address inside HA's own block (corporate 10.x), no HA address to compare, no IPv4. */
    OTHER,
}

object EstateShape {
    /** No estate-specific prefixes ship in the app; a caller that knows the home's prefixes passes them. */
    val DEFAULT_HOME_PREFIXES: List<String> = emptyList()

    /**
     * @param ipv4 the phone's IPv4 on this network (dotted quad), or null.
     * @param ipv6 the phone's global/ULA IPv6 addresses (any form), possibly empty.
     * @param haDialIp the private IP of Home Assistant (from `ha_dial`), or null: without it a
     *   foreign RFC 1918 block cannot be told from the estate's own family, so the answer is OTHER.
     */
    fun of(ipv4: String?, ipv6: List<String>, haDialIp: String?, homePrefixes: List<String> = DEFAULT_HOME_PREFIXES): Shape {
        val v4 = ipv4?.let(::parseV4)
        val v6 = ipv6.mapNotNull(::parseV6)
        val prefixes = homePrefixes.mapNotNull(::parsePrefix)
        if (v4 != null && prefixes.any { it.is4 && it.contains4(v4) }) return Shape.HOME
        if (v6.any { a -> prefixes.any { !it.is4 && it.contains6(a) } }) return Shape.HOME
        if (v4 == null) return Shape.OTHER
        val mine = block(v4)
        val ha = haDialIp?.let(::parseV4)?.let(::block)
        if (mine == null) return Shape.NOT_ESTATE // public or CGNAT (100.64/10 included): not a private LAN at all
        if (ha == null) return Shape.OTHER // no HA address to compare the block with
        return if (mine != ha) Shape.NOT_ESTATE else Shape.OTHER
    }

    // ---- parsing (strict: anything odd is "unknown", never a guess) ----

    private fun parseV4(s: String): Long? {
        val p = s.trim().split('.')
        if (p.size != 4) return null
        var v = 0L
        for (x in p) {
            if (x.isEmpty() || x.length > 3 || !x.all(Char::isDigit)) return null
            val n = x.toInt()
            if (n > 255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    /** The RFC 1918 block of an address: 10/8, 172.16/12 or 192.168/16, else null. */
    private fun block(v: Long): String? = when {
        v ushr 24 == 10L -> "10/8"
        v ushr 20 == 0xAC1L -> "172.16/12" // 172.16.0.0 - 172.31.255.255
        v ushr 16 == 0xC0A8L -> "192.168/16"
        else -> null
    }

    private class Prefix(val is4: Boolean, val v4: Long, val hi: Long, val lo: Long, val len: Int) {
        fun contains4(a: Long): Boolean = len == 0 || (a ushr (32 - len)) == (v4 ushr (32 - len))
        fun contains6(a: Pair<Long, Long>): Boolean {
            if (len == 0) return true
            return if (len <= 64) match(a.first, hi, len) else a.first == hi && match(a.second, lo, len - 64)
        }
        private fun match(x: Long, y: Long, bits: Int): Boolean = bits <= 0 || (x ushr (64 - bits)) == (y ushr (64 - bits))
    }

    private fun parsePrefix(s: String): Prefix? {
        val i = s.indexOf('/')
        if (i < 0) return null
        val len = s.substring(i + 1).trim().toIntOrNull() ?: return null
        val addr = s.substring(0, i).trim()
        return if (':' in addr) {
            val a = parseV6(addr) ?: return null
            if (len !in 0..128) null else Prefix(false, 0, a.first, a.second, len)
        } else {
            val a = parseV4(addr) ?: return null
            if (len !in 0..32) null else Prefix(true, a, 0, 0, len)
        }
    }

    /** An IPv6 address as two 64-bit halves, or null. Handles `::`, drops a `%zone`, rejects the rest. */
    private fun parseV6(raw: String): Pair<Long, Long>? {
        val s = raw.trim().substringBefore('%').lowercase()
        if (s.isEmpty() || s.count { it == ':' } < 2 || s.contains(":::")) return null
        val halves = s.split("::")
        if (halves.size > 2) return null
        fun groups(p: String): List<Int>? {
            if (p.isEmpty()) return emptyList()
            val out = ArrayList<Int>()
            for (g in p.split(':')) {
                if (g.isEmpty() || g.length > 4 || !g.all { it in '0'..'9' || it in 'a'..'f' }) return null
                out.add(g.toInt(16))
            }
            return out
        }
        val head = groups(halves[0]) ?: return null
        val tail = if (halves.size == 2) (groups(halves[1]) ?: return null) else emptyList()
        val all: List<Int> = if (halves.size == 2) {
            val fill = 8 - head.size - tail.size
            if (fill < 1) return null
            head + List(fill) { 0 } + tail
        } else {
            if (head.size != 8) return null
            head
        }
        var hi = 0L
        var lo = 0L
        for (i in 0 until 4) hi = (hi shl 16) or all[i].toLong()
        for (i in 4 until 8) lo = (lo shl 16) or all[i].toLong()
        return hi to lo
    }
}
