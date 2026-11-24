package com.meda.portico.core.call

import com.meda.portico.core.ha.ContentCaller
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ha.RosterEntry
import com.meda.portico.core.push.InMemoryPushJournalStore
import com.meda.portico.core.push.PushJournal
import java.util.TimeZone

/** A house-ring harness: the coordinator over fakes, every switch a variable a test flips. */
class HouseRingRig {
    var enabled = true
    var receiveOn = true
    var dndOn = false
    var paused = false
    var doorBusy = false
    var callLive = false
    var endpoint: String? = "ep-alice"
    var buffer = false
    var now = 1_759_700_001_000L

    val me = "ep-alice"
    var profile = ContentProfile(
        phone = "", rev = "r", cameras = emptyList(), actions = emptyList(), endpoint = me,
        callers = mapOf(
            "kitchen" to ContentCaller("Kitchen screen", "dashboard", emptyList(), emptyList(), emptyList()),
            "*" to ContentCaller(null, null, emptyList(), emptyList(), emptyList()),
        ),
        roster = listOf(RosterEntry("r-bob", "ep-bob", "phone", "Bob's phone", true, null)),
    )

    val cmds = FakeCommands()
    val sched = FakeScheduler()
    val shown = mutableListOf<HouseRing>()
    val dismissed = mutableListOf<Pair<String, HouseRingCoordinator.Dismiss>>()
    val answers = mutableListOf<Pair<String, String>>()
    val store = InMemoryPushJournalStore()
    val journal = PushJournal(store, { now }, { TimeZone.getTimeZone("UTC") })

    val ui = object : HouseRingUi {
        override fun show(ring: HouseRing) { shown += ring }
        override fun dismiss(callId: String, why: HouseRingCoordinator.Dismiss) { dismissed += callId to why }
    }

    val c = HouseRingCoordinator(
        enabled = { enabled }, receiveOn = { receiveOn }, dndOn = { dndOn }, paused = { paused }, doorBusy = { doorBusy },
        callLive = { callLive }, myEndpoint = { endpoint }, profile = { profile }, commands = cmds,
        answerHouse = { id, name -> answers += id to name }, ui = ui, scheduler = sched, clockMs = { now },
        post = { it() }, journal = journal, bufferWhileUnknown = { buffer },
    )

    val journalText: String get() = journal.text()

    fun ring(callId: String = "hso-1", from: String = "kitchen", to: String = me, snapshot: Boolean = false) =
        c.onHouseCall(HouseCallEvent(callId, "ringing", null, from, to, snapshot))

    fun pushRing(callId: String = "hso-1759700000000000000-1-a1b2c3", fromRef: String = HouseCallers.refFor("ep-bob"), exp: Long = 1_759_700_045_000L, seq: Int = 1) =
        c.onPush(HousePush.Ring(callId, fromRef, seq, 1_759_700_000_000L, exp), now)
}
