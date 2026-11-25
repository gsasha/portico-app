package com.meda.portico.core.connlog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-762 A4: the Go panic read from GoLog: frames kept as function names, everything else dropped, window, caps, one record. */
private const val EXIT = 1_762_000_000_000L

class GoPanicTest {
    private fun entry(msAgo: Long, text: String): String {
        val ms = EXIT - msAgo
        return "%d.%03d  4242  4260 E GoLog   : %s".format(ms / 1000, ms % 1000, text)
    }

    /** A real-looking tsnet panic: the message carries a tailnet host, an IP and a key; file lines carry paths. */
    private fun panicText(): List<String> = listOf(
        "panic: dial tcp 100.101.102.103:443: no route to host (peer cam-gate.tail1a2b3.ts.net) ${"tskey"}-auth-kAbCdEf123456 [recovered]",
        "\t/root/go/pkg/mod/golang.org/toolchain@v0.0.1-go1.26.linux-arm64/src/runtime/panic.go:770 +0x132",
        "[signal SIGSEGV: segmentation violation code=0x1 addr=0x0 pc=0x6f2a10]",
        "",
        "goroutine 87 [running]:",
        "tailscale.com/ipn/ipnlocal.(*LocalBackend).setNetMapLocked(0xc0001d6000, 0xc0007a2000)",
        "\t/root/go/pkg/mod/tailscale.com@v1.80.0/ipn/ipnlocal/local.go:4821 +0x1d4",
        "tailscale.com/net/netmon.(*Monitor).pump(0xc0000c3200, {0x1a2b3c, 0xc00012a000})",
        "\t/root/go/pkg/mod/tailscale.com@v1.80.0/net/netmon/netmon.go:301 +0x88",
        "github.com/meda/portico/gocore/tailnet.(*Node).run.func1()",
        "\t/src/gocore/tailnet/tailnet.go:210 +0x40",
        "main.worker[...](0x1, {...})",
        "created by github.com/meda/portico/gocore/tailnet.Start in goroutine 1",
        "\t/src/gocore/tailnet/tailnet.go:150 +0x120",
    )

    private fun log(vararg parts: List<String>, msAgo: Long = 1000) = parts.flatMap { it }.map { entry(msAgo, it) }

    @Test
    fun keepsFunctionNamesOnlyAndDropsMessageAndPaths() {
        val t = GoPanic.traceOf(log(panicText()), EXIT)
        assertEquals(
            listOf(
                "go panic", "ipnlocal.(LocalBackend).setNetMapLocked", "netmon.(Monitor).pump", "tailnet.(Node).run.func1",
                "main.worker", "created by tailnet.Start",
            ),
            t,
        )
        val all = t.joinToString("\n")
        for (bad in listOf("100.101", "ts.net", "tskey", "cam-gate", "/root", "go:", "0x", "no route", "dial")) assertFalse(all.contains(bad), bad)
    }

    @Test
    fun framesPassTheServerFilterShape() {
        val ok = Regex("[A-Za-z0-9_.$:()<> -]+")
        for (l in GoPanic.traceOf(log(panicText()), EXIT)) assertTrue(ok.matches(l) && CrashRecord.textOk(l, 240), l)
    }

    @Test
    fun frameShapeIsStrict() {
        assertEquals("a.B", GoPanic.frameOf("x/y/a.B(0x1)"))
        assertNull(GoPanic.frameOf("\tx/y/a.go:12 +0x4"))
        assertNull(GoPanic.frameOf("  a.B()"))
        assertNull(GoPanic.frameOf("a.B(host cam.example.com)")) // argument text outside the closed set: dropped
        assertNull(GoPanic.frameOf("a.B(http://h/x)"))
        assertNull(GoPanic.frameOf("gopkg.in/yaml%2ev3.Unmarshal(0x1)").also { /* % is allowed in the path, name must be clean */ })
        assertNull(GoPanic.frameOf("pkg.Do-It(0x1)"))
        assertNull(GoPanic.frameOf("exit status 2"))
        assertNull(GoPanic.frameOf("panic: boom at 10.1.2.3"))
        assertNull(GoPanic.frameOf("net.me.cloud.Do(0x1)")) // host-like name: dropped by the host filter
        assertNull(GoPanic.frameOf("a.B(0x1) token=zzz"))
    }

    @Test
    fun capsAtThirtyLines() {
        val many = listOf("panic: x", "goroutine 1 [running]:") + List(100) { "pkg.F$it(0x1)" }
        val t = GoPanic.traceOf(log(many), EXIT)
        assertEquals(30, t.size)
        assertEquals("go panic", t[0])
    }

    @Test
    fun longNameIsDropped() {
        val big = "p." + "A".repeat(61) + "(0x1)"
        assertNull(GoPanic.frameOf(big))
    }

    @Test
    fun onlyTheWindowAroundTheExitIsRead() {
        assertTrue(GoPanic.traceOf(log(panicText(), msAgo = 5 * 60_000), EXIT).isEmpty()) // an older panic is not pinned on this exit
        assertTrue(GoPanic.traceOf(log(panicText(), msAgo = -30_000), EXIT).isEmpty()) // after the exit
        assertEquals(6, GoPanic.traceOf(log(panicText(), msAgo = 59_000), EXIT).size)
    }

    @Test
    fun theLastPanicBlockWins() {
        val first = log(listOf("panic: a", "goroutine 1 [running]:", "old.One(0x1)"), msAgo = 30_000)
        val second = log(listOf("panic: b", "goroutine 2 [running]:", "new.Two(0x1)"), msAgo = 1_000)
        assertEquals(listOf("go panic", "new.Two"), GoPanic.traceOf(first + second, EXIT))
    }

    @Test
    fun noPanicOrNoGoroutineOrNoStampsMeansNothing() {
        assertTrue(GoPanic.traceOf(null, EXIT).isEmpty())
        assertTrue(GoPanic.traceOf(emptyList(), EXIT).isEmpty())
        assertTrue(GoPanic.traceOf(log(listOf("tsnet: up", "a.B(0x1)")), EXIT).isEmpty())
        assertTrue(GoPanic.traceOf(log(listOf("panic: recovered in callback")), EXIT).isEmpty())
        assertTrue(GoPanic.traceOf(listOf("panic: x", "goroutine 1 [running]:", "a.B(0x1)"), EXIT).isEmpty()) // no stamp, no window
        assertTrue(GoPanic.traceOf(listOf("garbage \u0000\u0001", "9999999999.999 junk"), EXIT).isEmpty())
    }

    // ---- through the reporter: one record, switch, rate, de-dup ----

    private class Fx(val exits: List<PlatformExit>, var on: Boolean = true, val lines: List<String>?) {
        val sent = ArrayList<String>()
        var reads = 0
        var cursor: Long? = EXIT - 100_000
        fun run(crash: Long? = null) = ExitReporter(
            source = { exits }, readCursor = { cursor }, writeCursor = { cursor = it }, enabled = { on }, lastCrashMs = { crash },
            enqueue = { sent.add(it) }, newId = { "id" + sent.size }, now = { EXIT + 3000 }, upMs = { 1 }, appVersion = "0.6.24-alpha", sdk = 34,
            model = "Pixel 7", release = "14", goLog = { reads++; lines },
        ).run(null)
    }

    private fun native(ts: Long = EXIT) = PlatformExit(ExitRecord.R_CRASH_NATIVE, 100, ts) { listOf("at java.lang.Object.wait(Native Method)", "*** *** ***") }

    @Test
    fun goFramesRideOnTheOneNativeExitRecord() {
        val f = Fx(listOf(native()), lines = log(panicText()))
        f.run()
        assertEquals(1, f.sent.size)
        val o = Json.parseToJsonElement(f.sent[0]).jsonObject
        assertEquals("exit", o["kind"]!!.jsonPrimitive.content)
        assertEquals("crash_native", o["reason"]!!.jsonPrimitive.content)
        val tr = o["trace"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("go panic", tr[0])
        assertEquals("at java.lang.Object.wait(Native Method)", tr.last())
        assertEquals(7, tr.size)
        f.run() // the cursor moved: the same exit is not sent again
        assertEquals(1, f.sent.size)
    }

    @Test
    fun switchOffReadsNoLogcatAndSendsNothing() {
        val f = Fx(listOf(native()), on = false, lines = log(panicText()))
        f.run()
        assertTrue(f.sent.isEmpty())
        assertEquals(0, f.reads)
        assertEquals(EXIT, f.cursor) // not held back for later
    }

    @Test
    fun otherReasonsAndJavaCrashesDoNotReadGoLog() {
        val f = Fx(listOf(PlatformExit(ExitRecord.R_ANR, 100, EXIT), PlatformExit(ExitRecord.R_CRASH, 100, EXIT - 10)), lines = log(panicText()))
        f.run(crash = EXIT - 20) // the Java crash was already reported by A2
        assertEquals(0, f.reads)
        assertEquals(1, f.sent.size)
    }

    @Test
    fun brokenOrEmptyLogcatStillSendsThePlatformExit() {
        val none = Fx(listOf(native()), lines = null)
        none.run()
        assertEquals(1, none.sent.size)
        val boom = Fx(listOf(native()), lines = emptyList())
        ExitReporter(
            source = { boom.exits }, readCursor = { boom.cursor }, writeCursor = {}, enabled = { true }, lastCrashMs = { null },
            enqueue = { boom.sent.add(it) }, newId = { "i" }, now = { EXIT }, upMs = { 1 }, appVersion = "1", sdk = 34, model = null, release = null,
            goLog = { throw IllegalStateException("logcat gone") },
        ).run(null)
        assertEquals(1, boom.sent.size)
        assertEquals(listOf("at java.lang.Object.wait(Native Method)"), Json.parseToJsonElement(boom.sent[0]).jsonObject["trace"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun anOlderPanicIsNotPinnedOnALaterNativeExit() {
        val f = Fx(listOf(native()), lines = log(panicText(), msAgo = 10 * 60_000))
        f.run()
        val tr = Json.parseToJsonElement(f.sent[0]).jsonObject["trace"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertFalse(tr.contains("go panic"))
    }
}
