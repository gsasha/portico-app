package com.meda.portico.ring

import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.push.Decision
import com.meda.portico.core.push.PushMessage
import com.meda.portico.core.push.Source
import com.meda.portico.core.ring.Channel
import com.meda.portico.core.ring.DoorSlots
import com.meda.portico.core.ring.NoteKind
import com.meda.portico.core.ring.NotificationSpec
import com.meda.portico.core.ring.PermissionState
import com.meda.portico.fcm.OutcomeDispatcher
import com.meda.portico.fcm.PushReceiver
import com.meda.portico.core.ha.Door
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.InMemoryDoorsCacheStore
import com.meda.portico.store.FakeSharedPreferences
import com.meda.portico.store.PrefsCallStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The notifier path end to end on the JVM: an FCM data map -> PushReceiver -> Decision ->
 * OutcomeDispatcher -> AndroidOutcomeHandler -> a recording [RingPoster]. Nothing rings: the
 * poster only records what it was asked to do.
 */
class AndroidOutcomeHandlerTest {
    private val t = 1_760_000_000_000L
    private val door = "01J9ENTRYGATEXXXXXXXXXXXXX"
    private val call = "gso-1760000000000-12-a1b2c3d4e5f6"

    private class RecordingPoster : RingPoster {
        val events = ArrayList<String>()
        val posted = ArrayList<NotificationSpec>()
        var throwOnPost = false
        override fun post(spec: NotificationSpec, slot: Int?, doorName: String?) {
            events += "post ${spec.kind} slot=$slot name=$doorName"
            if (throwOnPost) throw IllegalStateException("refused")
            posted += spec
        }
        override fun cancelRing(slot: Int) { events += "cancel $slot" }
        override fun armBackstop(slot: Int, doorRef: String, callId: String, atMs: Long) { events += "arm $slot at=$atMs" }
        override fun cancelBackstop(slot: Int) { events += "disarm $slot" }
    }

    private val lines = ArrayList<String>()
    private val log = HaLog { level, msg -> lines += "$level $msg" }
    private val poster = RecordingPoster()
    private val ringPrefs = FakeSharedPreferences()
    private var ringOn = true
    private var now = t
    private val ringState = RingState(ringPrefs)
    private val handler = AndroidOutcomeHandler(
        poster, ringState, DoorSlots(ringState), { PermissionState() }, { ringOn },
        { if (it == door) "Gate" else null }, { now }, log,
    )
    private val store = PrefsCallStore(FakeSharedPreferences())
    private val doors = DoorsCache(InMemoryDoorsCacheStore(listOf(Door("gate-sip", door, driver = "gate_sip_observer", name = "Gate"))))
    private val dispatcher = OutcomeDispatcher(handler, { log.log(HaLog.Level.INFO, "wake") }, { ringOn }, log)
    private val receiver = PushReceiver(store, doors::knownDoorRefs, dispatcher, log)

    private fun push(kind: String, seq: Int, extra: Map<String, String> = emptyMap()) =
        receiver.onMessage(mapOf("pv" to "2", "kind" to kind, "door_ref" to door, "call_id" to call, "seq" to "$seq", "t" to "$t") + extra, now)

    @Test
    fun `ring arms the backstop before posting, then answered elsewhere stops it and posts the status`() {
        push("ring", 1, mapOf("exp" to "${t + 45_000}"))
        assertEquals(listOf("arm 0 at=${t + 60_000}", "post RING slot=0 name=Gate"), poster.events)
        assertTrue(handler.isRinging(door, call))
        val ring = poster.posted.single()
        assertEquals(Channel.RING, ring.channel)
        assertEquals(60_000L, ring.timeoutMs)

        poster.events.clear()
        push("answered", 2, mapOf("by" to "other"))
        assertEquals(listOf("cancel 0", "disarm 0", "post ANSWERED_ELSEWHERE slot=0 name=Gate"), poster.events)
        assertFalse(handler.isRinging(door, call))

        poster.events.clear()
        push("ended", 3)
        assertEquals(listOf("post ANSWERED_ELSEWHERE slot=0 name=Gate"), poster.events, "silent re-post, nothing stopped")
        assertTrue(poster.posted.last().onlyAlertOnce)
    }

    @Test
    fun `ended unanswered stops the ring and posts Missed call`() {
        push("ring", 1, mapOf("exp" to "${t + 45_000}"))
        poster.events.clear()
        push("ended", 3)
        assertEquals(listOf("cancel 0", "disarm 0", "post MISSED slot=0 name=Gate"), poster.events)
    }

    @Test
    fun `a late ring posts only Missed call, and stops nothing`() {
        now = t + 45_000 + 15_001
        push("ring", 1, mapOf("exp" to "${t + 45_000}"))
        assertEquals(listOf("post MISSED slot=0 name=Gate"), poster.events)
    }

    @Test
    fun `decline and the backstop stop only the call that is ringing`() {
        push("ring", 1, mapOf("exp" to "${t + 45_000}"))
        poster.events.clear()
        handler.stopRing(door, "another-call-id", "declined")
        assertTrue(poster.events.isEmpty(), "a stale button for another call stops nothing")
        handler.stopRing(door, call, "expired")
        assertEquals(listOf("cancel 0", "disarm 0"), poster.events)
        assertFalse(handler.isRinging(door, call))
    }

    @Test
    fun `ring switch off - nothing posted, the call still recorded`() {
        ringOn = false
        push("ring", 1, mapOf("exp" to "${t + 45_000}"))
        assertTrue(poster.events.isEmpty())
        assertEquals("RINGING", store.get(com.meda.portico.core.push.CallKey(door, call))?.state?.name)
    }

    @Test
    fun `kind test posts one quiet notification, never a ring`() {
        receiver.onMessage(mapOf("pv" to "2", "kind" to "test", "t" to "$t"), now)
        assertEquals(listOf("post TEST slot=null name=null"), poster.events)
        assertEquals(Channel.STATUS, poster.posted.single().channel)
    }

    @Test
    fun `a socket ring and then the push ring for the same call ring once`() {
        Decision.receive(store, PushMessage.Ring(door, call, 1, t, null, Source.WS), t, setOf(door)).let(dispatcher::dispatch)
        push("ring", 1, mapOf("exp" to "${t + 45_000}"))
        assertEquals(1, poster.posted.count { it.kind == NoteKind.RING })
    }

    @Test
    fun `a poster failure is journalled, never thrown, and the backstop is already armed`() {
        poster.throwOnPost = true
        push("ring", 1, mapOf("exp" to "${t + 45_000}"))
        assertEquals("arm 0 at=${t + 60_000}", poster.events.first())
        assertTrue(lines.any { it.startsWith("ERROR") && "IllegalStateException" in it })
    }

    /**
     * The ring notifier's own lines never carry the full call id. (PR-B's `PushLog.decision` line,
     * "portico push: ...", logs call ids in full BY DESIGN, to match the server's journal; that
     * merged decision is outside this PR and is flagged in its description.)
     */
    @Test
    fun `the notifier path never logs the full call id`() {
        push("ring", 1, mapOf("exp" to "${t + 45_000}"))
        handler.stopRing(door, call, "declined")
        push("answered", 2, mapOf("by" to "other"))
        push("ended", 3)
        receiver.onMessage(mapOf("pv" to "2", "kind" to "test", "t" to "$t"), now)
        val ringLines = lines.filter { "portico ring:" in it }
        assertTrue(ringLines.size >= 5, "$ringLines")
        for (line in ringLines) assertFalse(call in line, "full call id in: $line")
        assertTrue(ringLines.any { "call=gso-17…(${call.length})" in it }, "masked by Redact.prefix: $ringLines")
    }
}
