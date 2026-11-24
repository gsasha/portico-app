package com.meda.portico.core.push

import com.meda.portico.core.ha.CallEvent
import com.meda.portico.core.ring.NotificationPlanner
import com.meda.portico.core.ring.PermissionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.TimeZone

/** The journal's lines: the time, the shortening, the outcome names, the environment, and what can never appear. */
class PushJournalFormatTest {
    private val jlm = TimeZone.getTimeZone("Asia/Jerusalem") // UTC+3 on 2026-10-22
    private val utc = TimeZone.getTimeZone("UTC")
    private val door = "01J9ENTRYGATEXXXXXXXXXXXXX"
    private val call = "gso-1760000000000-12-a1b2c3d4e5f6"
    private val now = Instant.parse("2026-10-22T05:48:23.990Z").toEpochMilli() // 08:48:23.990 in Jerusalem
    private val t = now - 160
    private val known = setOf(door)
    private val env = PushEnv(foreground = false, fullScreen = true, ringChannel = "ok", ringImportance = 4, notifications = true, dnd = "off")

    private fun ring(exp: Long? = now + 45_000, seq: Int = 1, source: Source = Source.FCM) =
        PushMessage.Ring(door, call, seq, t, exp, source)

    private fun decide(msg: PushMessage, store: CallStore = InMemoryCallStore(), at: Long = now, known: Set<String> = this.known) =
        Decision.receive(store, msg, at, known)

    private fun line(msg: PushMessage, outcome: Outcome, tz: TimeZone = jlm, k: Int? = 1, e: PushEnv = env) =
        PushJournalFormat.decisionLine(now, tz, msg, outcome, k, e)

    // ---- time -------------------------------------------------------------------------------------------

    @Test
    fun `time is the phone's local clock with milliseconds`() {
        assertEquals("08:48:23.990", PushJournalFormat.time(now, jlm))
        assertEquals("05:48:23.990", PushJournalFormat.time(now, utc))
        assertEquals("00:00:00.000", PushJournalFormat.time(Instant.parse("2026-10-22T21:00:00Z").toEpochMilli(), jlm), "midnight wraps")
        assertEquals("23:59:59.999", PushJournalFormat.time(Instant.parse("2026-10-22T20:59:59.999Z").toEpochMilli(), jlm))
        assertEquals("23:59:59.999", PushJournalFormat.time(-1L, utc), "before the epoch still wraps into the day")
    }

    // ---- the push line ----------------------------------------------------------------------------------

    @Test
    fun `a ring push line carries kind, shortened ids, seq, call time, known count, env and the outcome`() {
        val msg = ring()
        val l = line(msg, decide(msg))
        assertEquals(
            "08:48:23.990 push ring door=01J9EN…(26) call=gso-17…(33) seq=1 t=08:48:23.830 age=160ms known=1 until=08:49:23.990 " +
                "fg=bg fsi=yes chan=ok/4 notif=on dnd=off -> Ring",
            l,
        )
    }

    @Test
    fun `every outcome is named, with the silent or missed reason`() {
        val ringMsg = ring()
        val answered = PushMessage.Answered(door, call, 2, t + 5_000, "other")
        val ended = PushMessage.Ended(door, call, 3, t + 9_000)
        val s = InMemoryCallStore()
        assertTrue(line(ringMsg, decide(ringMsg, s)).endsWith("-> Ring"))
        assertTrue(line(ringMsg, decide(ringMsg, s)).endsWith("-> Silent(stale)"))
        assertTrue(line(answered, decide(answered, s)).endsWith("-> Dismiss"))
        assertTrue(line(ended, decide(ended, s)).endsWith("-> Silent(after_terminal)"))
        assertTrue(line(ringMsg, decide(ringMsg, known = emptySet())).endsWith("-> Silent(unknown_door)"))
        val late = ring(exp = now - 60_000)
        assertTrue(line(late, decide(late)).endsWith("-> Missed(late_ring)"))
        val s2 = InMemoryCallStore()
        decide(ringMsg, s2)
        assertTrue(line(ended, decide(ended, s2)).endsWith("-> Missed(ended_unanswered)"))
        val test = PushMessage.Test(t)
        assertEquals("08:48:23.990 push test t=08:48:23.830 age=160ms known=1 fg=bg fsi=yes chan=ok/4 notif=on dnd=off -> Quiet", line(test, decide(test)))
        val ign = PushMessage.parse(mapOf("pv" to "9", "kind" to "ring", "door_ref" to "SECRET-DOOR-REF-1234567890"))
        val il = line(ign, decide(ign))
        assertTrue(il.contains("push ignored(unknown_version)") && il.endsWith("-> Silent(ignored)"), il)
        assertFalse(il.contains("SECRET"), "an ignored message's contents never reach the line")
    }

    @Test
    fun `a socket message says ws and a missing probe reads as a question mark`() {
        val msg = ring(exp = null, source = Source.WS)
        val l = line(msg, decide(msg), k = null, e = PushEnv())
        assertTrue(l.startsWith("08:48:23.990 ws ring door="), l)
        assertTrue(l.contains("known=? "), l)
        assertTrue(l.contains("fg=? fsi=? chan=?/? notif=? dnd=?"), l)
    }

    @Test
    fun `the environment's words are whitelisted, so a surprise value cannot carry text`() {
        val l = line(ring(), decide(ring()), e = PushEnv(ringChannel = "Gate Door Alice", dnd = "https://x.example", ringImportance = 3))
        assertTrue(l.contains("chan=?/3") && l.contains("dnd=?"), l)
        assertFalse(l.contains("Alice") || l.contains("x.example"), l)
    }

    @Test
    fun `the lines about a decision that failed name the exception class and never its message`() {
        val l = PushJournalFormat.failedLine(now, jlm, ring(), IllegalStateException("token=abcdef0123456789abcdef"), env)
        assertTrue(l.contains("decide-failed ex=IllegalStateException"), l)
        assertFalse(l.contains("abcdef"), l)
        val anon = PushJournalFormat.stepFailedLine(now, jlm, "ring", object : RuntimeException("x") {})
        assertTrue(anon.endsWith("ex=?"), anon)
    }

    // ---- the socket and the handler ----------------------------------------------------------------------

    @Test
    fun `each portico calls event kind is a word with the door and call shortened`() {
        fun ev(e: CallEvent) = PushJournalFormat.socketEventLine(now, jlm, e)
        assertEquals("08:48:23.990 ws event=ringing door=01J9EN…(26) call=gso-17…(33)", ev(CallEvent.Ringing("door-id-1", door, call)))
        assertTrue(ev(CallEvent.Ringing("door-id-1", door, call, snapshot = true)).endsWith("call=gso-17…(33) snapshot"))
        assertTrue(ev(CallEvent.Answered("d", door, call)).contains("event=answered"))
        assertTrue(ev(CallEvent.Active("d", door, call)).contains("event=active"))
        assertTrue(ev(CallEvent.Ended("d", door, call, inferred = true)).endsWith("inferred"))
        assertEquals("08:48:23.990 ws event=idle door=01J9EN…(26)", ev(CallEvent.NoLiveCall("d", door)))
        val unknown = ev(CallEvent.Ringing("HA-DOOR-ID-0123456789", null, call))
        assertTrue(unknown.contains("door=unknown doorid=HA-DOO…(21)"), unknown)
    }

    @Test
    fun `the handler line says what was posted, whether it stops a ring, and the permission state used`() {
        val msg = ring()
        val out = decide(msg)
        val plan = NotificationPlanner.plan(out, now, true, PermissionState(), null)
        val l = PushJournalFormat.handledLine(now, jlm, out, plan, PermissionState())
        assertTrue(l.startsWith("08:48:23.990 ui Ring door=01J9EN…(26) call=gso-17…(33) post=ring+fsi"), l)
        assertTrue(l.contains("stop=no") && l.contains("notif=on ringchan=on high=yes statuschan=on fsi=yes"), l)

        val denied = PermissionState(notificationsEnabled = true, ringChannelHigh = false, fullScreenAllowed = false)
        val plan2 = NotificationPlanner.plan(out, now, true, denied, null)
        val l2 = PushJournalFormat.handledLine(now, jlm, out, plan2, denied)
        assertTrue(l2.contains("warn=") && l2.contains("high=no") && l2.contains("fsi=no"), l2)

        val answered = decide(PushMessage.Answered(door, call, 2, t + 5_000, "other"), InMemoryCallStore().also { decide(msg, it) })
        val l3 = PushJournalFormat.handledLine(now, jlm, answered, NotificationPlanner.plan(answered, now, true, PermissionState(), null), PermissionState())
        assertTrue(l3.contains("ui Dismiss") && l3.contains("post=answered_elsewhere") && l3.contains("stop=yes"), l3)
    }

    @Test
    fun `gated, dropped and stop lines use words and shortened ids only`() {
        val out = decide(ring())
        assertEquals(
            "08:48:23.990 ui Ring door=01J9EN…(26) call=gso-17…(33) not-alerted why=ring_switch_off",
            PushJournalFormat.gatedLine(now, jlm, out, "ring switch off"),
        )
        assertEquals("08:48:23.990 push dropped kind=ring why=paused", PushJournalFormat.droppedLine(now, jlm, "ring", "paused"))
        assertEquals("08:48:23.990 push dropped kind=? why=?", PushJournalFormat.droppedLine(now, jlm, "gso-1760000000000-12", "Ring 5"))
        assertEquals(
            "08:48:23.990 ui stop why=decline door=01J9EN…(26) call=gso-17…(33) stopped",
            PushJournalFormat.stopLine(now, jlm, "decline", door, call, true),
        )
        assertTrue(PushJournalFormat.stopLine(now, jlm, "backstop", door, call, false).endsWith("not-ringing"))
    }

    // ---- redaction -----------------------------------------------------------------------------------------

    @Test
    fun `no line of any kind holds a full id, however it was produced`() {
        val secretCall = "gso-1760000000000-12-a1b2c3d4e5f6"
        val secretDoor = "01J9ENTRYGATEXXXXXXXXXXXXX"
        val s = InMemoryCallStore()
        val msgs = listOf(
            PushMessage.Ring(secretDoor, secretCall, 1, t, now + 45_000, Source.FCM),
            PushMessage.Answered(secretDoor, secretCall, 2, t + 1, "other"),
            PushMessage.Ended(secretDoor, secretCall, 3, t + 2),
            PushMessage.Ring(secretDoor, "second-" + secretCall, 1, t, null, Source.WS),
        )
        val lines = ArrayList<String>()
        for (m in msgs) {
            val o = decide(m, s)
            lines += line(m, o)
            lines += PushJournalFormat.handledLine(now, jlm, o, NotificationPlanner.plan(o, now, true, PermissionState(), null), PermissionState())
            lines += PushJournalFormat.gatedLine(now, jlm, o, "paused")
            lines += PushJournalFormat.failedLine(now, jlm, m, RuntimeException(secretCall), env)
            lines += PushJournalFormat.stopLine(now, jlm, "decline", secretDoor, secretCall, true)
        }
        lines += PushJournalFormat.socketEventLine(now, jlm, CallEvent.Ringing(secretDoor, secretDoor, secretCall))
        lines += PushJournalFormat.socketEventLine(now, jlm, CallEvent.Ringing(secretDoor, null, secretCall))
        for (l in lines) {
            assertFalse(l.contains(secretCall) || l.contains("a1b2c3d4e5f6"), l)
            assertFalse(l.contains(secretDoor) || l.contains("ENTRYGATE"), l)
            assertTrue(Regex("[A-Za-z0-9_-]{16,}").findAll(l).none { r -> r.value.any(Char::isDigit) }, "a long id-like run in: $l")
            assertTrue(l.length <= PushJournal.MAX_LINE, l)
        }
    }

    @Test
    fun `scrub removes urls, tokens and codes and leaves a normal line alone`() {
        val normal = "08:48:23.990 push ring door=01J9EN…(26) call=gso-17…(33) seq=1 t=08:48:23.830 age=160ms known=1 -> Ring"
        assertEquals(normal, PushJournalFormat.scrub(normal))
        assertEquals("x <url> y", PushJournalFormat.scrub("x https://ha.example.com:8123/api?token=abc y"))
        assertEquals("a <id> b", PushJournalFormat.scrub("a ABCD-EFGH-IJKL-MNOP-1234 b"), "an enrolment-code-shaped run")
        assertEquals("a <id> b", PushJournalFormat.scrub("a " + "z".repeat(60) + " b"))
        assertEquals("ForegroundServiceStartNotAllowedException", PushJournalFormat.scrub("ForegroundServiceStartNotAllowedException"))
        assertEquals("a    b", PushJournalFormat.scrub("a\u0000\r\n\u0007b"))
        assertEquals("", PushJournalFormat.scrub("   \t "))
    }

    // ---- hold, switch, resume (M7) -------------------------------------------------------------------------------

    private enum class Weird { BAD_ID_A1B2C3D4E5F6G7H8I9J0 }

    @Test
    fun `a hold line is the time, the step word, two shortened ids and only enum names and numbers`() {
        assertEquals(
            "08:48:23.990 hold switched door=01J9EN…(26) call=gso-17…(33) ms=30000",
            PushJournalFormat.holdLine(now, jlm, HoldStep.SWITCHED, door, call, null, 30_000, null),
        )
        assertEquals(
            "08:48:23.990 hold resume-refused call=gso-17…(33) result=not_held",
            PushJournalFormat.holdLine(now, jlm, HoldStep.RESUME_REFUSED, null, call, com.meda.portico.core.call.SwitchResult.NOT_HELD, null, null),
        )
        assertEquals("08:48:23.990 hold focus-lost n=2", PushJournalFormat.holdLine(now, jlm, HoldStep.FOCUS_LOST, null, null, null, null, 2))
        assertEquals(
            "08:48:23.990 hold held-ended door=01J9EN…(26) call=gso-17…(33) result=remote_hangup ms=0",
            PushJournalFormat.holdLine(now, jlm, HoldStep.HELD_ENDED, door, call, com.meda.portico.core.call.EndReason.REMOTE_HANGUP, 0, null),
        )
        assertTrue(PushJournalFormat.holdLine(now, jlm, HoldStep.HOLD_LIMIT, door, call, com.meda.portico.core.call.EndReason.HOLD_TIMEOUT, 120_000, null).endsWith("result=hold_timeout ms=120000"))
    }

    @Test
    fun `no hold line of any step holds a full id, and an enum that is not a plain word becomes a question mark`() {
        for (step in HoldStep.values()) {
            val l = PushJournalFormat.holdLine(now, jlm, step, door, call, Weird.BAD_ID_A1B2C3D4E5F6G7H8I9J0, 5, 1)
            assertFalse(l.contains(call) || l.contains("a1b2c3d4e5f6") || l.contains(door) || l.contains("ENTRYGATE"), l)
            assertTrue(l.contains("result=?"), l)
            assertTrue(Regex("[A-Za-z0-9_-]{16,}").findAll(l).none { r -> r.value.any(Char::isDigit) }, l)
            assertTrue(l.length <= PushJournal.MAX_LINE, l)
        }
        // an empty or missing id is visible, never blank
        assertTrue(PushJournalFormat.holdLine(now, jlm, HoldStep.SWITCHED, "", "", null, null, null).contains("door=<none> call=<none>"))
    }
}
