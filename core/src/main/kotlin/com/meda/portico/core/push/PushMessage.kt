package com.meda.portico.core.push

/** Which delivery path a call message came by. Both write the same [CallStore]. */
enum class Source { FCM, WS }

/** Why a data map was not turned into a call message. Journalled; never shown to the user. */
enum class IgnoreReason {
    /** `pv` absent or not [Contract.PV] (spec §4.1: "ignores versions it does not know"). */
    UNKNOWN_VERSION,

    /** `kind` is none of ring / answered / ended / test, or `ct` (the call type) is not `door` (a house call, until M7). */
    UNKNOWN_KIND,

    /** A required key is missing, or a value does not parse (contract case "missing keys"). */
    MALFORMED,
}

/**
 * One message about a door call, as received. Built from an FCM data map by [parse], or from a
 * `portico/calls/subscribe` event by [SocketEvents] (PR-D). Parsing never throws: anything it cannot
 * use becomes [Ignored].
 */
sealed interface PushMessage {

    /** The three call kinds share these fields. */
    sealed interface CallMessage : PushMessage {
        val doorRef: String
        val callId: String
        val seq: Int
        val tMs: Long
        val source: Source
    }

    /**
     * A door is ringing. [expMs] is the server's `t + ring_window`; it is null only for a ring
     * seen live on the socket, which carries no expiry.
     */
    data class Ring(
        override val doorRef: String,
        override val callId: String,
        override val seq: Int,
        override val tMs: Long,
        val expMs: Long?,
        override val source: Source = Source.FCM,
    ) : CallMessage

    /** The call was answered by someone else (`by` is always `"other"` on the wire, or absent). */
    data class Answered(
        override val doorRef: String,
        override val callId: String,
        override val seq: Int,
        override val tMs: Long,
        val by: String?,
        override val source: Source = Source.FCM,
    ) : CallMessage

    /** The call ended. */
    data class Ended(
        override val doorRef: String,
        override val callId: String,
        override val seq: Int,
        override val tMs: Long,
        override val source: Source = Source.FCM,
    ) : CallMessage

    /** v1.1 `kind: "test"` (spec §8.3 step 2; contract case "test"). Not a call. */
    data class Test(val tMs: Long?) : PushMessage

    /**
     * Not usable. [detail] names the offending key or value class, never the value itself, so it
     * is safe to log even when a garbled field held something sensitive.
     */
    data class Ignored(val reason: IgnoreReason, val detail: String) : PushMessage

    companion object {
        /** Parse an FCM data map (every value a string). Never throws. */
        fun parse(data: Map<String, String?>): PushMessage {
            val pv = data["pv"] ?: return Ignored(IgnoreReason.UNKNOWN_VERSION, "pv missing")
            if (pv != Contract.PV) return Ignored(IgnoreReason.UNKNOWN_VERSION, "pv not ${Contract.PV}")
            val kind = data["kind"] ?: return Ignored(IgnoreReason.MALFORMED, "kind missing")
            when (kind) {
                Contract.KIND_RING, Contract.KIND_ANSWERED, Contract.KIND_ENDED -> Unit
                Contract.KIND_TEST -> {
                    val t = data["t"]
                    return Test(t?.let { parseEpochMs(it) })
                }
                else -> return Ignored(IgnoreReason.UNKNOWN_KIND, "kind not recognised")
            }
            // push/v2: the call type comes BEFORE door_ref is required. A house call (ct "house", M7) carries a caller
            // reference instead of a door and must never be read as a malformed door call; on 0.5.0 it is ignored.
            val ct = data["ct"]
            if (ct != null && ct != Contract.CT_DOOR) return Ignored(IgnoreReason.UNKNOWN_KIND, "ct not recognised")
            val doorRef = id(data, "door_ref") ?: return malformed(data, "door_ref")
            val callId = id(data, "call_id") ?: return malformed(data, "call_id")
            val seq = data["seq"]?.let { parseSeq(it) } ?: return malformed(data, "seq")
            val t = data["t"]?.let { parseEpochMs(it) } ?: return malformed(data, "t")
            return when (kind) {
                Contract.KIND_RING -> {
                    val exp = data["exp"]?.let { parseEpochMs(it) } ?: return malformed(data, "exp")
                    Ring(doorRef, callId, seq, t, exp)
                }
                Contract.KIND_ANSWERED -> Answered(doorRef, callId, seq, t, data["by"])
                else -> Ended(doorRef, callId, seq, t)
            }
        }

        private fun id(data: Map<String, String?>, key: String): String? =
            data[key]?.takeIf { Contract.ID_RE.matches(it) }

        private fun malformed(data: Map<String, String?>, key: String): Ignored =
            Ignored(IgnoreReason.MALFORMED, if (data[key] == null) "$key missing" else "$key unparseable")

        /** Plain decimal digits only (no sign, no spaces), 1..18 digits so it fits a Long. */
        private fun parseEpochMs(s: String): Long? =
            if (s.length in 1..18 && s.all { it in '0'..'9' }) s.toLong() else null

        private fun parseSeq(s: String): Int? =
            if (s.length in 1..9 && s.all { it in '0'..'9' }) s.toInt().takeIf { it >= 1 } else null
    }
}
