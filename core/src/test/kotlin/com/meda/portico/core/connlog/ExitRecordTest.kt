package com.meda.portico.core.connlog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-762 A3: the exit record's shape (portico-ha `sanitize_diag`), the trace filter, de-dup, and the start-up marker. */
class ExitRecordTest {
    private val NOW = 1_762_000_000_000L
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private class Fixture(var exits: List<PlatformExit>, var cursor: Long? = null, var on: Boolean = true, var crash: Long? = null) {
        val sent = ArrayList<String>()
        var n = 0
        fun reporter(now: Long) = ExitReporter(
            source = { exits }, readCursor = { cursor }, writeCursor = { cursor = it }, enabled = { on }, lastCrashMs = { crash },
            enqueue = { sent.add(it) }, newId = { "id" + (n++) }, now = { now }, upMs = { 1_000 }, appVersion = "0.6.24-alpha", sdk = 34,
            model = "Pixel 7", release = "14",
        )
    }

    private fun ex(reason: Int, ts: Long, imp: Int = 100, trace: List<String>? = null) = PlatformExit(reason, imp, ts) { trace }

    @Test
    fun shapeIsTheServersKeySet() {
        val o = obj(ExitRecord.build("anr", "foreground", "startup", listOf("at a.B.c(B.java:1)"), "abc123", "0.6.24-alpha", 34, NOW, 5000, 99, "Pixel 7", "14")!!)
        assertEquals(setOf("kind", "id", "app", "sdk", "t", "up", "age", "model", "rel", "reason", "importance", "phase", "trace"), o.keys)
        assertEquals("exit", o["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun badInputsYieldNoRecord() {
        assertNull(ExitRecord.build("", null, null, emptyList(), "i", "1", 34, NOW, 0, 0, null, null))
        assertNull(ExitRecord.build("anr", null, null, emptyList(), "bad id", "1", 34, NOW, 0, 0, null, null))
        assertNull(ExitRecord.build("anr", null, null, emptyList(), "i", "1", 34, -1, 0, 0, null, null))
    }

    @Test
    fun traceKeepsOnlyStrictJavaFramesAndCaps() {
        val good = "  at com.meda.portico.AppGraph.start(AppGraph.kt:12)"
        val lines = listOf(
            "\"main\" prio=5 tid=1 Blocked", "  native: #00 pc 000000000007d4c8  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+28)",
            "  at java.lang.Object.wait(Native Method)", good, "  at a.b.Foo.me(Foo.java:3)", // host-like (last label is a TLD): dropped
            "  at a.b.C.d(C.java:1) token=abc", "  at x.y.Z.q(/data/user/0/p/files/Z.java:4)", "  at http://h.io/x.y(Z.java:1)",
        )
        val t = ExitRecord.traceOf(lines)
        assertEquals(listOf("at java.lang.Object.wait(Native Method)", "at com.meda.portico.AppGraph.start(AppGraph.kt:12)"), t)
        assertEquals(30, ExitRecord.traceOf(List(100) { "at a.b.C.m$it(C.java:$it)" }).size)
        assertTrue(ExitRecord.traceOf(null).isEmpty())
    }

    @Test
    fun reportsAbnormalOnlyOldestFirstAndAdvancesCursor() {
        val f = Fixture(listOf(ex(ExitRecord.R_ANR, NOW - 1000), ex(ExitRecord.R_USER_REQUESTED, NOW - 2000), ex(ExitRecord.R_SIGNALED, NOW - 3000)), cursor = NOW - 10_000)
        f.reporter(NOW).run(null)
        assertEquals(listOf("signaled", "anr"), f.sent.map { obj(it)["reason"]!!.jsonPrimitive.content })
        assertEquals(NOW - 1000, f.cursor)
        f.sent.clear()
        f.reporter(NOW + 5).run(null) // same platform list: nothing is sent twice
        assertTrue(f.sent.isEmpty())
    }

    @Test
    fun firstRunLooksBackOneDayOnly() {
        val f = Fixture(listOf(ex(ExitRecord.R_SIGNALED, NOW - 3 * 24 * 3600_000L), ex(ExitRecord.R_LOW_MEMORY, NOW - 3600_000L)))
        f.reporter(NOW).run(null)
        assertEquals(listOf("low_memory"), f.sent.map { obj(it)["reason"]!!.jsonPrimitive.content })
    }

    @Test
    fun crashAlreadyReportedByTheHandlerIsSkipped() {
        val f = Fixture(listOf(ex(ExitRecord.R_CRASH, NOW - 4000)), cursor = NOW - 10_000, crash = NOW - 4500)
        f.reporter(NOW).run(null)
        assertTrue(f.sent.isEmpty())
        val g = Fixture(listOf(ex(ExitRecord.R_CRASH, NOW - 4000)), cursor = NOW - 10_000, crash = NOW - 400_000) // an unrelated old crash
        g.reporter(NOW).run(null)
        assertEquals(1, g.sent.size)
    }

    @Test
    fun anrTraceIsFilteredAndNativeTraceToo_butOtherReasonsReadNone() {
        var read = 0
        val tr = PlatformExit(ExitRecord.R_ANR, 100, NOW - 100) { read++; listOf("at a.b.C.d(C.java:1)", "pc 0000dead /system/lib/x.so") }
        val oth = PlatformExit(ExitRecord.R_SIGNALED, 100, NOW - 200) { read++; listOf("at a.b.C.d(C.java:1)") }
        val f = Fixture(listOf(tr, oth), cursor = NOW - 1000)
        f.reporter(NOW).run(null)
        assertEquals(1, read)
        val anr = f.sent.map(::obj).first { it["reason"]!!.jsonPrimitive.content == "anr" }
        assertEquals(listOf("at a.b.C.d(C.java:1)"), anr["trace"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(f.sent.map(::obj).first { it["reason"]!!.jsonPrimitive.content == "signaled" }.containsKey("trace"))
    }

    @Test
    fun switchOffSendsNothingButAdvancesTheCursor() {
        val f = Fixture(listOf(ex(ExitRecord.R_ANR, NOW - 1000)), cursor = NOW - 10_000, on = false)
        f.reporter(NOW).run(NOW - 50_000)
        assertTrue(f.sent.isEmpty())
        assertEquals(NOW - 1000, f.cursor)
    }

    @Test
    fun markerWithNoPlatformDataIsStartupDied() { // API < 30
        val f = Fixture(emptyList(), cursor = NOW - 10_000)
        f.reporter(NOW).run(NOW - 5000)
        val o = obj(f.sent.single())
        assertEquals("startup_died", o["reason"]!!.jsonPrimitive.content)
        assertEquals("startup", o["phase"]!!.jsonPrimitive.content)
        assertEquals(NOW - 5000, o["t"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun markerWithAPlatformExitTagsItStartupInsteadOfDuplicating() {
        val f = Fixture(listOf(ex(ExitRecord.R_CRASH, NOW - 1000)), cursor = NOW - 10_000)
        f.reporter(NOW).run(NOW - 5000)
        val o = obj(f.sent.single())
        assertEquals("crash", o["reason"]!!.jsonPrimitive.content)
        assertEquals("startup", o["phase"]!!.jsonPrimitive.content)
    }

    @Test
    fun culledPushOnlyProcessIsNotStartupDied() {
        val f = Fixture(listOf(ex(ExitRecord.R_SIGNALED, NOW - 1000, imp = 400)), cursor = NOW - 10_000)
        f.reporter(NOW).run(NOW - 5000)
        val o = obj(f.sent.single())
        assertEquals("signaled", o["reason"]!!.jsonPrimitive.content)
        assertFalse(o.containsKey("phase"))
    }

    @Test
    fun aThrowingSourceNeverEscapes() {
        var fault = 0
        ExitReporter({ error("boom") }, { null }, {}, { true }, { null }, { error("q") }, { "i" }, { NOW }, { 0 }, "1", 34, null, null) { fault++ }.run(NOW - 1)
        assertTrue(fault >= 1)
    }

    @Test
    fun startupMarkerRoundTripAndBrokenStoreIsTotal() {
        var v: String? = null
        val m = StartupMarker({ v }, { v = it })
        assertNull(m.pendingSince()); m.set(42); assertEquals(42L, m.pendingSince()); m.clear(); assertNull(m.pendingSince())
        StartupMarker({ error("x") }, { error("x") }).also { it.set(1); it.clear(); assertNull(it.pendingSince()) }
    }

    @Test
    fun wordsCoverEveryPlatformReason() {
        assertEquals("anr", ExitRecord.reasonWord(6)); assertEquals("unknown", ExitRecord.reasonWord(0)); assertEquals("cached", ExitRecord.importanceWord(400))
        assertTrue((1..16).all { ExitRecord.reasonWord(it) != "unknown" })
    }
}
