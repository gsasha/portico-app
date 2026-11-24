package com.meda.portico.core.call

import com.meda.portico.core.Hex
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ha.Label
import java.security.MessageDigest

/**
 * Who is calling, from what a house ring carries (M7 3b-B).
 *
 * A `house_call` event names ENDPOINT ids (`from`, `to`); a push (`ct: house`) carries only `from_ref`, an opaque reference the
 * server derives from the endpoint id (`house.py` `house_from_ref`: `e-` and the first 32 hex of sha256 of the id), so a push never
 * holds a name or an id. Neither is ever shown: the name comes from the document the phone already holds (its `callers` and its
 * roster), and a caller the document does not know is shown as no name at all ("Someone"), never as the id or the reference.
 */
object HouseCallers {
    /** What the server puts in a push for [endpointId]. */
    fun refFor(endpointId: String): String =
        "e-" + Hex.encode(MessageDigest.getInstance("SHA-256").digest(endpointId.toByteArray(Charsets.UTF_8))).substring(0, 32)

    /** Every endpoint id this phone's document names: its callers (but not the `*` fallback) and its roster. */
    fun knownEndpoints(profile: ContentProfile): List<String> =
        (profile.callers.keys.filter { it != "*" } + profile.roster.map { it.id }).distinct()

    /** The endpoint id a push's [ref] stands for, or null when the document names no endpoint with that reference. */
    fun endpointForRef(profile: ContentProfile, ref: String): String? = knownEndpoints(profile).firstOrNull { refFor(it) == ref }

    /** The cleaned name of [endpointId] from the document, or null (an unknown caller, or one with a blank name). Never the id. */
    fun nameFor(profile: ContentProfile, endpointId: String?): String? {
        if (endpointId == null) return null
        val fromCallers = profile.callers[endpointId]?.name?.let { Label.clean(it, Label.MAX) }
        if (fromCallers != null) return fromCallers
        return profile.roster.firstOrNull { it.id == endpointId }?.name?.let { Label.clean(it, Label.MAX) }
    }
}
