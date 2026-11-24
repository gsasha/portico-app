package com.meda.portico.core.live

/**
 * A tile scrolled out of view keeps its live stream for [GRACE_MS], then loses it (0.6.50). Pure: the caller passes the clock (ms), so a
 * test needs no timers. [onScreen] is the plan's `LiveTileIn.onScreen`: true while the tile is in view AND for [GRACE_MS] after it left;
 * a tile that comes back within the grace is simply on screen again (nothing was stopped, so the stream continues without a restart).
 * Only a tile that WAS in view gets the grace: one that is below the fold from the start never opens a stream.
 * [forget] / [clear] drop the pending stops (a tile that is gone, the screen going to the background or being destroyed).
 */
class OffscreenGrace {
    companion object {
        /** How long a stream outlives its tile leaving the viewport. The one value. */
        const val GRACE_MS = 30_000L
    }

    private val leftAt = HashMap<String, Long>()
    private val seen = HashSet<String>()

    /** [inView] is the raw "at least partly in the viewport"; [now] the clock. */
    fun onScreen(id: String, inView: Boolean, now: Long): Boolean {
        if (inView) {
            seen.add(id)
            leftAt.remove(id)
            return true
        }
        if (id !in seen) return false
        val since = leftAt.getOrPut(id) { now }
        return now - since < GRACE_MS
    }

    /** True while [id] has a stop pending (it left the viewport and its grace has not run out). */
    fun pending(id: String, now: Long): Boolean = leftAt[id]?.let { now - it < GRACE_MS } == true

    fun forget(id: String) { leftAt.remove(id); seen.remove(id) }

    fun clear() { leftAt.clear(); seen.clear() }
}
