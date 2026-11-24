package com.meda.portico.core.call

import com.meda.portico.core.push.Contract

/**
 * A push (`push/v2.1`) about a HOUSE call: `ct: "house"`, an opaque `from_ref` and NO `door_ref` (portico-ha `push/payload.py`,
 * fixture `push_v2.json`). Parsed apart from [com.meda.portico.core.push.PushMessage] on purpose: the door decision never sees a
 * house message, so a house ring can neither ring as a door nor end a door call that happens to share an id (the fixture has both
 * cases). [parse] is null for everything that is not a well-formed house message, so such a map falls through to the door path
 * and is ignored there exactly as it was before 3b.
 */
sealed interface HousePush {
    val callId: String
    val fromRef: String
    val seq: Int
    val tMs: Long

    data class Ring(override val callId: String, override val fromRef: String, override val seq: Int, override val tMs: Long, val expMs: Long) : HousePush
    data class Answered(override val callId: String, override val fromRef: String, override val seq: Int, override val tMs: Long) : HousePush
    data class Ended(override val callId: String, override val fromRef: String, override val seq: Int, override val tMs: Long) : HousePush

    companion object {
        const val CT_HOUSE = "house"

        /** Whether the map says it is about a house call at all (`pv` 2, `ct` house), well-formed or not. */
        fun isHouse(data: Map<String, String?>): Boolean = data["pv"] == Contract.PV && data["ct"] == CT_HOUSE

        fun parse(data: Map<String, String?>): HousePush? {
            if (!isHouse(data)) return null
            val kind = data["kind"] ?: return null
            val callId = id(data, "call_id") ?: return null
            val fromRef = id(data, "from_ref") ?: return null
            val seq = data["seq"]?.let { digits(it, 9)?.toInt() }?.takeIf { it >= 1 } ?: return null
            val t = data["t"]?.let { digits(it, 18) } ?: return null
            return when (kind) {
                Contract.KIND_RING -> Ring(callId, fromRef, seq, t, data["exp"]?.let { digits(it, 18) } ?: return null)
                Contract.KIND_ANSWERED -> Answered(callId, fromRef, seq, t)
                Contract.KIND_ENDED -> Ended(callId, fromRef, seq, t)
                else -> null
            }
        }

        private fun id(data: Map<String, String?>, key: String): String? = data[key]?.takeIf { Contract.ID_RE.matches(it) }

        private fun digits(s: String, max: Int): Long? = if (s.length in 1..max && s.all { it in '0'..'9' }) s.toLong() else null
    }
}
