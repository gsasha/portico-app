package com.meda.portico.fcm

import com.meda.portico.core.ha.Door
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.push.Outcome
import com.meda.portico.core.push.SilentReason
import com.meda.portico.core.reg.RegValue
import com.meda.portico.store.FakeSharedPreferences
import com.meda.portico.store.PrefsCallStore
import com.meda.portico.store.PrefsDoorsCacheStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pause on the push path: while paused EVERY push (ring, answered, ended, test, garbage) is dropped
 * first thing in the service's logic, before the decision, the call store, the dispatcher or any
 * notification, with one masked log line. After the resume a ring from before it is not rung.
 */
class PushPauseTest {
    private val door = "01J9ENTRYGATE"
    private val t = 1_760_000_000_000L
    private val call = "gso-1760000000000000000-1-a1b2c3"
    private val callsPrefs = FakeSharedPreferences()
    private val statePrefs = FakeSharedPreferences().also { PrefsDoorsCacheStore(it).save(listOf(Door("gate-sip.example", door))) }
    private val doors = DoorsCache(PrefsDoorsCacheStore(statePrefs))
    private val store = PrefsCallStore(callsPrefs)
    private val handler = PushReceiverTest.RecordingHandler()
    private var wakes = 0
    private val logs = ArrayList<String>()
    private val log = HaLog { level, msg -> logs += "$level $msg" }
    private val dispatcher = OutcomeDispatcher(handler, { wakes++ }, { true }, log)
    private var paused = true
    private var pausedThrows = false
    private var resumedAt = 0L
    private val delivered = ArrayList<RegValue>()
    private val logic = MessagingServiceLogic(
        receiver = PushReceiver(store, doors::knownDoorRefs, dispatcher, log, resumedAt = { resumedAt }),
        deliver = { delivered += it },
        installationId = { "inst-1" },
        log = log,
        paused = { if (pausedThrows) error("unreadable") else paused },
    )

    private fun ring(callId: String = call, at: Long = t) =
        mapOf("pv" to "2", "kind" to "ring", "door_ref" to door, "call_id" to callId, "seq" to "1", "t" to "$at", "exp" to "${at + 45_000}")

    private val kinds: Map<String, Map<String, String?>> = mapOf(
        "ring" to ring(),
        "answered" to mapOf("pv" to "2", "kind" to "answered", "door_ref" to door, "call_id" to call, "seq" to "2", "t" to "${t + 5_000}", "by" to "other"),
        "ended" to mapOf("pv" to "2", "kind" to "ended", "door_ref" to door, "call_id" to call, "seq" to "3", "t" to "${t + 9_000}"),
        "test" to mapOf("pv" to "2", "kind" to "test", "t" to "$t"),
        "other" to mapOf("pv" to "2", "kind" to "door-opened-by-a-registration-cPYZbX0nQ0mJ9mVdK3x_-A"),
        "malformed" to mapOf("pv" to "2", "door_ref" to door),
    )

    @Test
    fun `while paused every kind is dropped before the store, the dispatcher and any notification`() {
        for ((name, data) in kinds) {
            logs.clear()
            logic.onMessage(data, t + 1_000, PushReceiver.PRIORITY_HIGH, PushReceiver.PRIORITY_HIGH)
            assertTrue(handler.calls.isEmpty(), "$name reached the handler: ${handler.calls}")
            assertEquals(0, wakes, "$name woke the socket")
            assertEquals(0, callsPrefs.commits, "$name wrote the call store")
            assertTrue(callsPrefs.all.isEmpty(), "$name left a record")
            assertEquals(1, logs.size, "$name: exactly one line: $logs")
        }
    }

    @Test
    fun `the drop line names only the kind - no call id, no door, no registration`() {
        for ((_, data) in kinds) logic.onMessage(data, t)
        assertEquals(
            listOf("ring", "answered", "ended", "test", "other", "other").map { "INFO portico push: dropped (paused) kind=$it" },
            logs,
        )
        for (l in logs) {
            assertFalse(call in l || "gso-" in l || door in l || "cPYZ" in l, l)
        }
    }

    @Test
    fun `not paused - the same ring rings, as before`() {
        paused = false
        logic.onMessage(ring(), t + 1_000)
        assertEquals(listOf("ring"), handler.calls)
        assertEquals(1, wakes)
    }

    @Test
    fun `FAIL OPEN - an unreadable pause flag rings`() {
        pausedThrows = true
        logic.onMessage(ring(), t + 1_000)
        assertEquals(listOf("ring"), handler.calls)
    }

    @Test
    fun `after the resume a ring from before it is history, a newer one rings`() {
        paused = false
        resumedAt = t + 60_000
        logic.onMessage(ring("gso-1760000050000000000-1-old", t + 50_000), t + 61_000)
        assertEquals(SilentReason.BEFORE_RESUME, (handler.outcomes.single() as Outcome.Silent).reason)
        assertEquals(0, wakes, "a withheld ring does not wake the socket")
        logic.onMessage(ring("gso-1760000062000000000-1-new", t + 62_000), t + 62_500)
        assertEquals(listOf("silent", "ring"), handler.calls)
    }

    @Test
    fun `a registration rotation while paused is kept for the resume (the socket is closed, so nothing is sent)`() {
        logic.onRegistered("cPYZbX0nQ0mJ9mVdK3x_-A")
        assertEquals(1, delivered.size, "the machine stores it and registers on the resume's auth_ok")
    }
}
