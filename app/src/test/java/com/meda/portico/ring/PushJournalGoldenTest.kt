package com.meda.portico.ring

import com.meda.portico.core.ha.CallEvent
import com.meda.portico.core.ha.Door
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.InMemoryDoorsCacheStore
import com.meda.portico.core.push.InMemoryPushJournalStore
import com.meda.portico.core.push.PushJournal
import com.meda.portico.core.push.PushJournalStore
import com.meda.portico.core.push.SocketCallSink
import com.meda.portico.core.ring.DoorSlots
import com.meda.portico.core.ring.NotificationSpec
import com.meda.portico.core.ring.PermissionState
import com.meda.portico.fcm.JournalingCallSink
import com.meda.portico.fcm.MessagingServiceLogic
import com.meda.portico.fcm.OutcomeDispatcher
import com.meda.portico.fcm.PushReceiver
import com.meda.portico.fcm.SocketOutcomeRoute
import com.meda.portico.store.FakeSharedPreferences
import com.meda.portico.store.PrefsCallStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

/**
 * GOLDEN: the journal is write-only observation. The same script of pushes and socket events runs through the real
 * receive path (PushReceiver -> Decision -> OutcomeDispatcher -> AndroidOutcomeHandler -> a recording poster, and the
 * socket sink beside it) with NO journal, with a journal, and with a journal whose store throws on every call; the
 * outcomes, the call store, the ring state, every notification posted or cancelled, the log lines and the socket wakes
 * must be identical.
 */
class PushJournalGoldenTest {
    private val t = 1_760_000_000_000L
    private val door = "01J9ENTRYGATEXXXXXXXXXXXXX"
    private val call = "gso-1760000000000-12-a1b2c3d4e5f6"
    private val call2 = "gso-1760000009000-13-ffeeddccbbaa"

    private class RecordingPoster : RingPoster {
        val events = ArrayList<String>()
        override fun post(spec: NotificationSpec, slot: Int?, doorName: String?) { events += "post $spec slot=$slot name=$doorName" }
        override fun cancelRing(slot: Int) { events += "cancel $slot" }
        override fun armBackstop(slot: Int, doorRef: String, callId: String, atMs: Long) { events += "arm $slot $doorRef $callId $atMs" }
        override fun cancelBackstop(slot: Int) { events += "disarm $slot" }
    }

    private val throwingStore = object : PushJournalStore {
        override fun read(): String? = error("read")
        override fun write(text: String): Boolean = error("write")
    }

    private inner class Rig(val journal: PushJournal?, val paused: Boolean = false, ringOn: Boolean = true) {
        val lines = ArrayList<String>()
        val log = HaLog { level, msg -> lines += "$level $msg" }
        val poster = RecordingPoster()
        val ringPrefs = FakeSharedPreferences()
        val callPrefs = FakeSharedPreferences()
        var now = t + 1_000
        var wakes = 0
        val ringState = RingState(ringPrefs)
        val handler = AndroidOutcomeHandler(
            poster, ringState, DoorSlots(ringState), { PermissionState() }, { ringOn },
            { if (it == door) "Gate" else null }, { now }, log, journal,
        )
        val store = PrefsCallStore(callPrefs)
        val doors = DoorsCache(InMemoryDoorsCacheStore(listOf(Door("gate-sip", door, driver = "gate_sip_observer", name = "Gate"))))
        val dispatcher = OutcomeDispatcher(handler, { wakes++ }, { ringOn }, log, journal = journal)
        val receiver = PushReceiver(store, doors::knownDoorRefs, dispatcher, log, journal = journal)
        val messaging = MessagingServiceLogic(receiver, {}, { null }, log, paused = { paused }, journal = journal)
        val route = SocketOutcomeRoute(dispatcher::dispatch, { paused }, log, journal, { now }, { doors.knownDoorRefs().size })
        val socket = SocketCallSink(store, doors, { now }, { m, o -> route.onOutcome(m, o) }, { log.log(HaLog.Level.INFO, it) })
        val sink = JournalingCallSink(socket, journal)

        fun push(kind: String, seq: Int, callId: String = call, doorRef: String = door, extra: Map<String, String> = emptyMap()) =
            messaging.onMessage(
                mapOf("pv" to "2", "kind" to kind, "door_ref" to doorRef, "call_id" to callId, "seq" to "$seq", "t" to "$t") + extra, now,
            )

        /** Everything a user or the next process could observe. */
        fun observed(): List<String> = poster.events + lines + listOf("wakes=$wakes", "calls=${callPrefs.all}", "ring=${ringPrefs.all}")

        fun script() {
            push("ring", 1, extra = mapOf("exp" to "${t + 45_000}"))
            push("ring", 1, extra = mapOf("exp" to "${t + 45_000}"))                        // duplicate
            push("ring", 1, callId = "x$call", doorRef = "UNKNOWN-DOOR-REF-1234567", extra = mapOf("exp" to "${t + 45_000}"))
            push("answered", 2, extra = mapOf("by" to "other"))
            push("ended", 3)
            now = t + 200_000
            push("ring", 1, callId = call2, extra = mapOf("exp" to "${t + 45_000}"))        // late ring
            push("test", 0)
            messaging.onMessage(mapOf("pv" to "9"), now)                                    // garbage
            messaging.onMessage(emptyMap(), now)
            sink.onCallEvent(CallEvent.Ringing("door-id-1", door, "ws-call-1"))
            sink.onCallEvent(CallEvent.Ringing("door-id-1", door, "ws-call-1"))             // duplicate
            sink.onCallEvent(CallEvent.Answered("door-id-1", door, "ws-call-1"))
            sink.onCallEvent(CallEvent.Ringing("door-id-9", null, "ws-call-2"))             // unknown door
            sink.onCallEvent(CallEvent.NoLiveCall("door-id-1", door))
            handler.stopRing(door, call, "decline")
            handler.stopAllRings("pause")
        }
    }

    private fun golden(paused: Boolean = false, ringOn: Boolean = true): List<String> {
        val base = Rig(null, paused, ringOn).also(Rig::script).observed()
        val withJournal = Rig(PushJournal(InMemoryPushJournalStore(), { 1L }, { TimeZone.getTimeZone("UTC") }), paused, ringOn)
            .also(Rig::script).observed()
        val broken = Rig(PushJournal(throwingStore, { 1L }, { TimeZone.getTimeZone("UTC") }, { error("probe") }), paused, ringOn)
            .also(Rig::script).observed()
        assertEquals(base, withJournal, "a journal changes nothing that is observable")
        assertEquals(base, broken, "a journal whose store and probe throw changes nothing either")
        return base
    }

    @Test
    fun `outcomes, notifications, store, ring state, logs and wakes are identical with the journal off, on, and broken`() {
        val base = golden()
        assertTrue(base.any { it.startsWith("post NotificationSpec(kind=RING") }, "the script really rang")
        assertTrue(base.any { it.contains("ANSWERED_ELSEWHERE") })
    }

    @Test
    fun `the same holds with the ring switch off and with the phone paused`() {
        val off = golden(ringOn = false)
        assertFalse(off.any { it.startsWith("post NotificationSpec(kind=RING") })
        val paused = golden(paused = true)
        assertFalse(paused.any { it.startsWith("post ") })
    }

    @Test
    fun `the journal tells the story of the script, in order, with no full id`() {
        val store = InMemoryPushJournalStore()
        val rig = Rig(PushJournal(store, { t + 1_000 }, { TimeZone.getTimeZone("UTC") }, { com.meda.portico.core.push.PushEnv(foreground = false) }))
        rig.script()
        val text = store.text!!
        val lines = text.lines().drop(1)
        assertTrue(lines.first().contains("push ring door=01J9EN…(26) call=gso-17…(33)") && lines.first().endsWith("-> Ring"), lines.first())
        assertEquals("ui Ring", lines[1].substringAfter(' ').substringBefore(" door="), lines[1])
        assertTrue(lines.any { it.contains("push ring") && it.endsWith("-> Silent(stale)") })
        assertTrue(lines.any { it.contains("-> Silent(unknown_door)") })
        assertTrue(lines.any { it.contains("push answered") && it.endsWith("-> Dismiss") })
        assertTrue(lines.any { it.contains("-> Missed(late_ring)") })
        assertTrue(lines.any { it.contains("push test") && it.endsWith("-> Quiet") })
        assertTrue(lines.any { it.contains("push ignored(unknown_version)") })
        assertTrue(lines.any { it.contains("ws event=ringing") } && lines.any { it.contains("ws event=idle") })
        assertTrue(lines.any { it.contains("ws event=ringing door=unknown doorid=door-i…(9)") })
        assertTrue(lines.any { it.contains("ws ring ") && it.endsWith("-> Ring") })
        assertTrue(lines.any { it.contains("ui stop why=decline") })
        assertTrue(lines.any { it.contains("ui stop-all why=pause n=") })
        // M7: the hold lines exist only while HoldConfig.HOLD_ENABLED (compile-time false), so a push-and-ring script has none
        assertFalse(lines.any { it.substringAfter(' ').startsWith("hold ") }, "no hold line with the gate off")
        for (id in listOf(call, call2, door, "a1b2c3d4e5f6", "ENTRYGATE", "UNKNOWN-DOOR-REF-1234567")) assertFalse(text.contains(id), id)
        assertTrue(lines.all { it.length <= PushJournal.MAX_LINE })
    }

    @Test
    fun `lines written by an earlier process come first, then the new process's`() {
        val store = InMemoryPushJournalStore()
        val first = Rig(PushJournal(store, { t }, { TimeZone.getTimeZone("UTC") }))
        first.push("ring", 1, extra = mapOf("exp" to "${t + 45_000}"))
        val before = store.text!!.lines().drop(1)
        val second = Rig(PushJournal(store, { t }, { TimeZone.getTimeZone("UTC") })) // the process died; a fresh graph on the same file
        second.push("test", 0)
        val after = store.text!!.lines().drop(1)
        assertEquals(before, after.take(before.size))
        assertTrue(after.drop(before.size).first().contains("push test"))
    }
}
