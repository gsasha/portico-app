package com.meda.portico.call

import com.meda.portico.AppGraph
import com.meda.portico.core.callbar.RingNow
import com.meda.portico.core.callbar.StationCall
import com.meda.portico.core.callbar.StationCalls

/**
 * APP-03: the call from the station as the main screen and its call bar draw it: the ring that is up on this phone, or the
 * answer under way. One read of the app's own state, mapped by `:core`'s [StationCalls]; nothing here decides anything.
 * A second ring while another call is live is not shown (the live call is; the ring is the notification only).
 */
fun AppGraph.stationCall(): StationCall? {
    val ring = ring.activeRings().entries.map { RingNow(it.key, it.value) }.let { all ->
        all.firstOrNull { answer.canAnswer(it.doorRef, it.callId) } ?: all.firstOrNull()
    }
    val state = answer.state()
    return StationCalls.of(
        ring = ring,
        answer = state,
        nameOf = { doors.nameFor(it) },
        doorIdOf = { doors.doorIdFor(it) },
        canAnswer = { answer.canAnswer(it.doorRef, it.callId) },
    )
}
