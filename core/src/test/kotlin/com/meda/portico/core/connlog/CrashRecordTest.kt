package com.meda.portico.core.connlog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-762 A2: the crash record's shape (portico-ha `sanitize_diag`), privacy, caps, and the handler's order. */
class CrashRecordTest {
    private fun el(cls: String, m: String, line: Int) = StackTraceElement(cls, m, "F.kt", line)

    private fun boom(msg: String, vararg frames: StackTraceElement) = IllegalStateException(msg).also { it.stackTrace = arrayOf(*frames) }

    private fun build(t: Throwable, thread: String? = "main", model: String? = "Pixel 7", id: String = "abc123def456") =
        CrashRecord.build(t, thread, id, "0.6.22-alpha", 34, 1_762_000_000_000L, 4_321L, model, "14")

    private fun obj(s: String?): JsonObject = Json.parseToJsonElement(s!!).jsonObject

    @Test
    fun shapeIsTheServersKeySetAndNothingElse() {
        val o = obj(build(boom("x", el("com.meda.portico.AppGraph", "start", 12), el("android.os.Handler", "run", 3))))
        assertEquals(setOf("kind", "id", "app", "sdk", "t", "up", "model", "rel", "thread", "chain", "frames"), o.keys)
        assertEquals("crash", o["kind"]!!.jsonPrimitive.content)
        assertEquals(1_762_000_000_000L, o["t"]!!.jsonPrimitive.content.toLong())
        assertEquals(listOf("java.lang.IllegalStateException"), o["chain"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("com.meda.portico.AppGraph.start:12"), o["frames"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun messageIsNeverIncluded() {
        val secret = "https://ha.example.com/api?token=SECRETSECRETSECRET user@home 10.1.2.3 /data/user/0/x"
        val t = boom(secret, el("com.meda.portico.A", "m", 1))
        val e = RuntimeException(secret, t)
        val text = build(e)!!
        assertFalse(text.contains("SECRET")); assertFalse(text.contains("10.1.2.3")); assertFalse(text.contains("example"))
        assertFalse(text.contains("user@home"))
    }

    @Test
    fun aClassNameThatLooksLikeAHostOrSecretIsDropped() {
        class Dummy
        val frames = arrayOf(el("com.meda.portico.Foo", "me", 5), el("com.meda.portico.Ok", "fine", 6))
        val o = obj(build(boom("m", *frames)))
        assertEquals(listOf("com.meda.portico.Ok.fine:6"), o["frames"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun framesAreOwnPackagesOnlyAndCappedAtEight() {
        val fr = (1..30).map { el("com.meda.portico.C$it", "m", it) } + el("com.other.X", "m", 1)
        val o = obj(build(boom("m", *fr.toTypedArray())))
        val out = o["frames"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(8, out.size)
        assertEquals("com.meda.portico.C1.m:1", out[0])
        assertTrue(out.all { it.startsWith("com.meda.portico.") })
    }

    @Test
    fun chainIsCappedAndSurvivesACycle() {
        var t: Throwable = RuntimeException()
        repeat(20) { t = RuntimeException(t) }
        assertEquals(8, CrashRecord.chainOf(t).size)
        val a = RuntimeException("a"); val b = RuntimeException("b", a)
        a.initCause(b)
        assertEquals(2, CrashRecord.chainOf(a).size)
    }

    @Test
    fun mangledKotlinMethodNamesAreCleaned() {
        val o = obj(build(boom("m", el("com.meda.portico.A\$lambda\$1", "foo-abc\$1", 9))))
        assertEquals(listOf("com.meda.portico.A\$lambda\$1.foo_abc\$1:9"), o["frames"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun oddThreadAndModelAreCleanedOrOmitted() {
        val o = obj(build(boom("m"), thread = "pool/1 @ x", model = "a@b.com"))
        assertEquals("pool_1 _ x", o["thread"]!!.jsonPrimitive.content)
        assertFalse(o.containsKey("model"))
    }

    @Test
    fun nothingToSayOrBadIdGivesNull() {
        class Weird : Exception()
        // an anonymous / local class name still has a chain entry, so force an unusable id instead
        assertNull(build(boom("m"), id = "bad id!"))
    }

    @Test
    fun rateLimitAllowsFivePerWindow() {
        var stored: String? = null
        val l = CrashRateLimit({ stored }, { stored = it })
        repeat(5) { assertTrue(l.tryAcquire(1_000L + it)) }
        assertFalse(l.tryAcquire(2_000L))
        assertTrue(l.tryAcquire(1_000L + 11 * 60 * 1000))
    }

    // ---- the handler ----

    private val order = mutableListOf<String>()
    private val err = boom("m", el("com.meda.portico.A", "m", 1))

    private fun handler(
        enabled: Boolean = true,
        flush: () -> Unit = { order.add("flush") },
        enqueue: (String) -> Unit = { order.add("enqueue") },
        budget: Long = 200,
    ) = CrashHandler(
        previous = { _, _ -> order.add("previous"); Unit },
        enabled = { enabled },
        flushJournal = flush,
        buildRecord = { e, th -> build(e, th.name) },
        enqueueDurable = enqueue,
        budgetMs = budget,
    )

    @Test
    fun flushThenRecordThenPrevious() {
        handler().uncaughtException(Thread.currentThread(), err)
        assertEquals(listOf("flush", "enqueue", "previous"), order)
    }

    @Test
    fun switchOffOnlyPrevious() {
        handler(enabled = false).uncaughtException(Thread.currentThread(), err)
        assertEquals(listOf("previous"), order)
    }

    @Test
    fun failingFlushStillQueuesRecordAndFailingEnqueueStillCallsPrevious() {
        handler(flush = { throw IllegalStateException("disk") }).uncaughtException(Thread.currentThread(), err)
        assertEquals(listOf("enqueue", "previous"), order)
        order.clear()
        handler(enqueue = { throw IllegalStateException("disk") }).uncaughtException(Thread.currentThread(), err)
        assertEquals(listOf("flush", "previous"), order)
    }

    @Test
    fun aHangingFlushIsAbandonedAfterTheBudget() {
        val t0 = System.nanoTime()
        handler(flush = { Thread.sleep(5_000) }, budget = 100).uncaughtException(Thread.currentThread(), err)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
        assertEquals(listOf("previous"), order)
    }

    @Test
    fun installingTwiceKeepsOneHandler() {
        val first = handler()
        assertTrue(CrashHandler.wrapping(first) { handler() } === first)
    }

    @Test
    fun reporterFlushNowHandsPendingLinesToTheSinkBeforeTheRecord() {
        val items = mutableListOf<String>()
        val rep = JournalReporter("0.6.22-alpha", { true }, { items.add("journal") }, { _, _ -> }, newId = { "id0000000001" })
        rep.offer("22:40:00.000 live start cam=front")
        assertEquals(1, rep.waiting())
        CrashHandler(null, { true }, rep::flushNow, { _, _ -> "{}" }, { items.add("crash") }).uncaughtException(Thread.currentThread(), err)
        assertEquals(listOf("journal", "crash"), items)
        assertEquals(0, rep.waiting())
    }

    @Test
    fun durableAddUsesTheDurableWrite() {
        var plain = 0; var durable = 0
        val q = ConnLogQueue(object : ConnLogStore {
            var text: String? = null
            override fun read() = text
            override fun write(text: String) { plain++; this.text = text }
            override fun writeDurable(text: String) { durable++; this.text = text }
        })
        q.add("""{"id":"a1"}""")
        q.add("""{"id":"a2"}""", durable = true)
        assertEquals(1, plain); assertEquals(1, durable); assertEquals(2, q.size())
    }
}
