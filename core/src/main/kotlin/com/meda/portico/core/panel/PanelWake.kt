package com.meda.portico.core.panel

import com.meda.portico.core.ha.ContentCommands.ErrorKind
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ha.Door

/**
 * The door filter of a panel (DEV-807, design section 6). The app hears every door's ring (`portico/calls/subscribe` is open to any login,
 * and push is per phone), so the panel decides itself: a door ring takes the panel when its door is in the `wake.doors` of the app's screen.
 *
 * `wake` is a list of door references: a new or changed Talk element stores the door's own `door.id` (`d-...`); an unchanged legacy element
 * may hold `<provider>:<id>` (design section 5, rules: "the app's door match normalises both"). The app's door has three names for the same
 * thing (id, `door_ref`, endpoint id), so a wake entry matches any of them.
 *
 * - `wake` ABSENT ([wake] null): ring for every door (a legacy document, a stale slug, a server without the field). Journalled by the caller.
 * - `wake` EMPTY: legal (a panel you chose not to ring), nothing rings.
 * - House calls are NOT judged here: they are judged by the phone rule (addressed to the panel's phone endpoint).
 *
 * One pure function serves the socket route and the push route, which both go through [com.meda.portico.core.push.Decision].
 */
object PanelWake {
    /** True when a ring at [doorRef] (a [Door] of the doors cache, or null when the cache does not know it) takes the panel. */
    fun accepts(wake: List<String>?, doorRef: String, door: Door?): Boolean {
        if (wake == null) return true
        if (wake.isEmpty()) return false
        for (w in wake) {
            if (w == doorRef) return true
            if (door != null && (w == door.doorId || w == door.doorRef || (door.endpointId != null && w == door.endpointId))) return true
        }
        return false
    }

    /** What the journal says about a wake list, for `wake-empty` and `wake-missing` (design section 15). */
    fun lineFor(wake: List<String>?): String? = when {
        wake == null -> "wake-missing"
        wake.isEmpty() -> "wake-empty"
        else -> null
    }
}

/** What to do with the screen slug after the server spoke. */
sealed interface SlugStep {
    /** Nothing changes. */
    data object Keep : SlugStep

    /** Name this slug from now on (`wall endpoint-adopted`). */
    data class Adopt(val slug: String) : SlugStep

    /** The slug is stale: forget it, ask without `endpoint`, and re-read `screens` (`wall endpoint-lost`). */
    data object Drop : SlugStep
}

/**
 * The screen slug a panel names in its content calls (`endpoint=<slug>`) and what happens when it goes stale (design section 6, "Stale slug").
 * A slug that was deleted, renamed or re-pointed makes the server answer `unauthorized` (or `unknown_endpoint`) on the named calls. The app then
 * drops the cached `wake`, asks without `endpoint`, and re-reads `screens`; until a slug is back the panel rings for every door (it fails loud,
 * not silent). If `screens` holds exactly one slug the app adopts it. `endpoint_ok` is false meanwhile.
 */
object PanelSlug {
    /** A refusal of `content/get` or `content/subscribe` that says the named slug is not this login's. */
    fun staleRefusal(kind: ErrorKind): Boolean = kind == ErrorKind.UNAUTHORIZED || kind == ErrorKind.UNKNOWN_ENDPOINT

    /** The server refused a named call. A refusal without a slug named says nothing about one. */
    fun afterRefusal(current: String?, kind: ErrorKind): SlugStep =
        if (current != null && staleRefusal(kind)) SlugStep.Drop else SlugStep.Keep

    /**
     * A profile arrived. With no slug named: exactly one entry in `screens` is adopted (more than one is ambiguous and left alone; none means no
     * screen is declared for this login). With a slug named: a profile that lists `screens` without it means it was re-pointed or deleted.
     */
    fun afterProfile(current: String?, profile: ContentProfile): SlugStep {
        val screens = profile.screens
        if (current == null) return if (screens.size == 1) SlugStep.Adopt(screens[0]) else SlugStep.Keep
        // a profile that came from a named call and says what it is: its `endpoint` is the slug. Otherwise it is stale only if `screens` is
        // present and does not list it
        if (screens.isNotEmpty() && current !in screens) return SlugStep.Drop
        return SlugStep.Keep
    }

    /**
     * `endpoint_ok` of the heartbeat: a slug is named, the last profile came back for it, and the phone endpoint is known. A phone that names
     * no slug (a stale or never-adopted one) is not ok.
     */
    fun endpointOk(current: String?, profile: ContentProfile): Boolean =
        current != null && profile.endpoint == current && profile.phoneEndpoint != null
}
