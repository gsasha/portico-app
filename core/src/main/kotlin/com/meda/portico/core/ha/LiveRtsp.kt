package com.meda.portico.core.ha

import com.meda.portico.core.net.Ipv4
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Where a live camera tile's video can be pulled from when the phone is away from home (DEV-713 S1b): the camera tile's additive
 * `live_rtsp` object. [main] is go2rtc's main stream, [sub] its sub stream when the admin listed one (`live_sub_streams`); the
 * server sends the object only on a live Frigate-backed tile once `live_rtsp_base` is set, never with credentials, and `main` is
 * always there when the object is.
 *
 * STRICT, because the address is handed to the tailnet dialer: only `rtsp://host[:port]/name` is accepted (see [validUrl]). Anything
 * else, in either member, makes the whole object read as ABSENT ([parse] returns null): the tile then has no away route and the page
 * is never dropped. The two members must also name the same host and port (one go2rtc), so a document cannot send the phone to a
 * second machine through the sub stream.
 */
data class LiveRtsp(val main: String, val sub: String? = null) {
    /** The address a tile of [size] pulls: a Small tile the sub stream (the main one when the server listed no sub), a Big tile the main stream (the owner, Q8). */
    fun forSize(size: CameraSize): String = if (size == CameraSize.SMALL) sub ?: main else main

    companion object {
        const val MAX_URL = 200
        const val MAX_NAME = 64
        private const val SCHEME = "rtsp://"

        /** The camera object's `live_rtsp` member read strictly; null (absent) when it is missing, not an object, or anything in it is not acceptable. */
        fun parse(el: JsonElement?): LiveRtsp? {
            val o = el as? JsonObject ?: return null
            val main = o.string("main") ?: return null
            if (!validUrl(main)) return null
            val subEl = o["sub"]
            val sub = if (subEl == null) null else (o.string("sub") ?: return null)
            if (sub != null && (!validUrl(sub) || authority(sub) != authority(main))) return null
            return LiveRtsp(main, sub)
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        /**
         * `rtsp://host[:port]/name` and nothing else: lowercase scheme; host an IPv4 address (four decimal parts, each 0 to 255) or a
         * host name (letters, digits and hyphens in labels of 1 to 63, 253 in all, not all numeric); an optional decimal port 1 to 65535;
         * one path segment `name` of `[A-Za-z0-9_-]{1,64}`. No user info (`@`), no IPv6, no query, fragment, escape, dot segment, trailing
         * slash or space; at most [MAX_URL] characters.
         */
        fun validUrl(url: String): Boolean {
            if (url.length > MAX_URL || !url.startsWith(SCHEME)) return false
            val rest = url.substring(SCHEME.length)
            val slash = rest.indexOf('/')
            if (slash <= 0) return false
            val auth = rest.substring(0, slash)
            val name = rest.substring(slash + 1)
            if (!validName(name)) return false
            val colon = auth.indexOf(':')
            val host = if (colon < 0) auth else auth.substring(0, colon)
            if (colon >= 0) {
                val port = auth.substring(colon + 1)
                if (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' }) return false
                if (port.toInt() !in 1..65535) return false
            }
            return validHost(host)
        }

        fun validName(name: String): Boolean = name.length in 1..MAX_NAME && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }

        /** The stream NAME of an address that passed [validUrl] (what the Journal writes; never the host); null for anything else. */
        fun nameOf(url: String): String? = if (validUrl(url)) url.substringAfterLast('/') else null

        /** `host[:port]` of a valid address, to compare two members. */
        private fun authority(url: String): String = url.substring(SCHEME.length).substringBefore('/')

        private fun validHost(host: String): Boolean {
            if (host.isEmpty() || host.length > 253) return false
            val labels = host.split('.')
            if (labels.all { l -> l.isNotEmpty() && l.all { it in '0'..'9' } }) return validIpv4(labels) // numeric only: must be a real dotted quad
            return labels.all { l ->
                l.length in 1..63 && !l.startsWith('-') && !l.endsWith('-') &&
                    l.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
            } && !labels.last().all { it in '0'..'9' }
        }

        /**
         * SEC-05: a numeric host is a canonical dotted quad (no leading zeros, which some resolvers read as octal) in a private or
         * tailnet range, like [com.meda.portico.core.net.UrlPolicy]'s. A public IP is never a stream's host; names are unchanged.
         */
        private fun validIpv4(parts: List<String>): Boolean = Ipv4.parseCanonical(parts)?.let { Ipv4.isPrivate(it) } == true
    }
}
