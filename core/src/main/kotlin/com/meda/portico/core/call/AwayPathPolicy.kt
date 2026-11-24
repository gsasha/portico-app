package com.meda.portico.core.call

import com.meda.portico.core.ha.LinkPath

/**
 * DEV-764: WebRTC or websocket audio frames for a door/house call. UDP media only works on the direct (home LAN)
 * path; on the tailnet the forwarder carries the signaling socket only. [LinkPath] is null while the socket is not
 * open (after a doze wake, mid-reconnect), so a null read must not be taken as "direct": it falls back to the last
 * path the app knew. Unknown with no history is not direct either, so frames.
 */
object AwayPathPolicy {
    data class Decision(val frames: Boolean, val why: String)

    fun decide(current: LinkPath?, last: LinkPath?): Decision = when {
        current == LinkPath.TAILNET -> Decision(true, "tailnet")
        current == LinkPath.DIRECT -> Decision(false, "direct")
        last == LinkPath.DIRECT -> Decision(false, "path-unknown-last-direct")
        last == LinkPath.TAILNET -> Decision(true, "path-unknown-last-tailnet")
        else -> Decision(true, "path-unknown")
    }

    /**
     * DEV-811 2.7: the same decision with Setup's "Call audio path" applied. The choice counts ONLY on the home network (where WebRTC
     * can work): on the tailnet or with the path unknown it is ignored and the reason says so, so a forced WebRTC can never leave an
     * answered door call with a leg that never connects.
     */
    fun decide(current: LinkPath?, last: LinkPath?, choice: PathChoice): Decision {
        val base = decide(current, last)
        if (choice == PathChoice.AUTO) return base
        if (base.frames) return Decision(true, base.why + "+choice-ignored")
        return Decision(choice == PathChoice.FRAMES, "forced-" + choice.name.lowercase())
    }
}
