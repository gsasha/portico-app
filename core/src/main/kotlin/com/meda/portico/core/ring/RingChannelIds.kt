package com.meda.portico.core.ring

/**
 * The ring channel family. Since 0.6.64 there is ONE ring channel, [BASE_ID] (the pre-0.6.58 "Ring and vibrate" id, so the user's per-channel
 * settings on Android's page survive). Earlier versions also made `_s<hash8>` (a picked sound, A-09) and `_mr[_s<hash8>]`, `_mv`, `_mq` (the
 * 0.6.58 ringing modes) channels; [staleIds] lists them, and the retired ones, for `Channels.ensure` to delete. The active id is never listed.
 */
object RingChannelIds {
    val BASE_ID: String = Channel.RING.id

    private val RING_FAMILY = Regex(Regex.escape(BASE_ID) + "(_s[0-9a-f]{8}|_m(r(_s[0-9a-f]{8})?|v|q))?")

    /** True for the base id and every id an earlier version derived from a sound or a mode. */
    fun isRingId(id: String): Boolean = RING_FAMILY.matches(id)

    /** Channel ids to delete given the ids that exist now: other ring-family ids, plus the retired ones. */
    fun staleIds(existing: Collection<String>, activeId: String = BASE_ID): List<String> =
        (existing.filter { isRingId(it) && it != activeId } + Channel.RETIRED_IDS)
            .filter { it != activeId }.distinct()
}
