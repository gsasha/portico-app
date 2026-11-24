package com.meda.portico.core.connlog

import com.meda.portico.core.call.AudioDirection
import com.meda.portico.core.call.AudioStats
import com.meda.portico.core.call.EndReason
import com.meda.portico.core.call.MicLevel
import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.live.LiveKey
import com.meda.portico.core.live.LiveWhy
import com.meda.portico.core.live.StopWhy
import com.meda.portico.core.push.AudioPhase
import com.meda.portico.core.push.InMemoryPushJournalStore
import com.meda.portico.core.push.PushJournal
import com.meda.portico.core.push.PushJournalFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The Journal reporter (DEV-713 / DEV-705): which lines, with what time, how batched, how bounded, and that it never blocks the writer. */
class JournalReporterTest {
    private val utc = TimeZone.getTimeZone("UTC")

    /** 2025-10-21T00:00:00Z. */
    private val midnight = 1_761_004_800_000L
    private val t0 = 1_761_000_000_000L // 2025-10-20T22:40:00Z, as the contract's lines

    private var now = t0
    private var on = true
    private var ids = 0
    private val items = mutableListOf<String>()
    private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
    private val faults = mutableListOf<Throwable>()
    private var sinkThrows = false
    private var zone: TimeZone = utc

    private val reporter = JournalReporter(
        appVersion = "0.6.16-alpha",
        enabled = { on },
        sink = { if (sinkThrows) throw IllegalStateException("disk") else items.add(it) },
        schedule = { d, t -> tasks.add(d to t) },
        onFault = { faults.add(it) },
        clockMs = { now },
        zone = { zone },
        newId = { "id%010d".format(++ids) },
    )

    private fun at(t: Long, body: String) = PushJournalFormat.time(t, zone) + " " + body
    private fun offer(body: String) = reporter.offer(at(now, body))

    /** Run every scheduled task whose delay is at most [upTo] (earliest first), the way the executor would after that long. */
    private fun runTasks(upTo: Long = Long.MAX_VALUE) {
        while (true) {
            val next = tasks.filter { it.first <= upTo }.minByOrNull { it.first } ?: return
            tasks.remove(next)
            next.second()
        }
    }

    private fun parsed() = items.map { Json.parseToJsonElement(it).jsonObject }
    private fun linesOf(o: JsonObject) = o["lines"]!!.jsonArray.map { it.jsonObject["line"]!!.jsonPrimitive.content }

    // ---- the time prefix ---------------------------------------------------------------------------------------------

    @Test
    fun `the time of day becomes the epoch on the local day`() {
        now = t0 + 5_000
        val s = JournalLines.stamp("22:40:00.250 live start cam=x", now, utc)
        assertEquals(t0 + 250, s.t)
        assertEquals("live start cam=x", s.body)
    }

    @Test
    fun `a line written in a zone ahead of UTC lands on the right instant`() {
        val jerusalem = TimeZone.getTimeZone("Asia/Jerusalem") // +03:00 on 2025-10-20
        val written = t0 + 123
        val s = JournalLines.stamp(PushJournalFormat.time(written, jerusalem) + " live stop", written + 700, jerusalem)
        assertEquals(written, s.t)
    }

    @Test
    fun `any instant survives the Journal's own prefix and back`() {
        for (zoneId in listOf("UTC", "Asia/Jerusalem", "America/Los_Angeles")) {
            val z = TimeZone.getTimeZone(zoneId)
            for (written in listOf(t0, midnight - 1, midnight, midnight + 1, midnight + 43_200_000, midnight + 86_399_999)) {
                val s = JournalLines.stamp(PushJournalFormat.time(written, z) + " live start", written + 40, z)
                assertEquals(written, s.t, "$zoneId $written")
            }
        }
    }

    @Test
    fun `a line written just before midnight and read just after belongs to yesterday`() {
        val written = midnight - 100
        val s = JournalLines.stamp(PushJournalFormat.time(written, utc) + " live stop", midnight + 50, utc)
        assertEquals(written, s.t)
        assertTrue(s.t < midnight)
    }

    @Test
    fun `a time a second ahead of the clock stays today, a time a minute ahead is yesterday's`() {
        val soon = JournalLines.stamp(PushJournalFormat.time(t0 + 1_000, utc) + " live x", t0, utc)
        assertEquals(t0 + 1_000, soon.t)
        val far = JournalLines.stamp(PushJournalFormat.time(t0 + 60_000, utc) + " live x", t0, utc)
        assertEquals(t0 + 60_000 - 86_400_000, far.t)
    }

    @Test
    fun `no prefix means the clock and the whole text`() {
        val s = JournalLines.stamp("live start cam=x", t0, utc)
        assertEquals(t0, s.t)
        assertEquals("live start cam=x", s.body)
        now = t0
        reporter.offer("live start cam=x")
        runTasks()
        assertEquals(t0, parsed().single()["lines"]!!.jsonArray.single().jsonObject["t"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `a prefix that is not a time is not stripped`() {
        for (raw in listOf("25:00:00.000 live x", "12:61:00.000 live x", "12:00:61.000 live x", "12:00:00.000live x", "1:00:00.000 live x", "12:00:00 live x")) {
            val s = JournalLines.stamp(raw, t0, utc)
            assertEquals(t0, s.t, raw)
            assertEquals(raw, s.body, raw)
            reporter.offer(raw)
        }
        assertEquals(0, reporter.waiting(), "none of them starts with live or audio")
        assertEquals(0, reporter.droppedInvalid)
    }

    // ---- which lines ---------------------------------------------------------------------------------------------------

    @Test
    fun `only lines whose first word is one of the reported words are kept`() {
        val kept = listOf(
            "live start cam=gate…(4) size=big stream=main ttff=840ms",
            "live thermal paused status=severe",
            "live fault at=sync err=IllegalStateException",
            "audio start answer call=gso-18…(31) up=pcmu/8000/20ms down=pcmu/8000/20ms sent=100 updrop=0 recv=99 downdrop=0 mic=low",
            "audio end answer call=gso-18…(31) up=? down=? sent=? updrop=? recv=? downdrop=? mic=? why=remote_hangup",
            "live",
        )
        val ignored = listOf(
            "connlog journal fault ex=IllegalStateException", "crash x", "exit x", "pushed ring x", "wsx open", "network x", "uix x", "livestock x", "Live start", "audiobook x", "xlive start", "",
            "ring live start", "live-start x",
        )
        for (l in kept) offer(l)
        for (l in ignored) offer(l)
        assertEquals(kept.size, reporter.waiting())
        assertEquals(0, reporter.droppedInvalid, "a line that is not ours is ignored, not counted as a drop")
        runTasks()
        assertEquals(kept, linesOf(parsed().single()))
    }

    @Test
    fun `every live and audio line the app writes is accepted by the server's rules`() {
        val journal = PushJournal(InMemoryPushJournalStore(), { now }, { utc })
        journal.lineObserver = reporter::offer
        val gate = LiveKey("camera.gate_intercom_main", CameraSize.BIG)
        val rtsp = LiveKey("camera.garden_east_cam", CameraSize.SMALL, "garden_east_cam_sub")
        journal.liveLog.started(gate, "main", 840)
        journal.liveLog.started(rtsp, "sub", 1_900)
        journal.liveLog.failed(gate, LiveWhy.DECODER, 2, false)
        journal.liveLog.failed(rtsp, LiveWhy.AWAY, 0, true)
        journal.liveLog.stopped(gate, StopWhy.HIDDEN, 61_500)
        journal.liveLog.thermal(4, true)
        journal.liveLog.thermal(1, false)
        journal.liveLog.fault("sync", "IllegalStateException")
        val dir = AudioDirection("pcmu", 8000, 20, 100, 0)
        journal.audio(AudioPhase.START, "answer", "gso-18abcdef-0123456789", AudioStats(dir, dir, MicLevel.LOW), false, null)
        journal.audio(AudioPhase.END, "away", "gso-18abcdef-0123456789", AudioStats(), true, EndReason.REMOTE_HANGUP)
        journal.audio(AudioPhase.END, "answer", null, null, false, EndReason.NETWORK_LOST)
        assertEquals(11, reporter.waiting())
        assertEquals(0, reporter.droppedInvalid, "the formatter's own lines are never refused by the rules")
        assertEquals(journal.lines().size, 11)
    }

    @Test
    fun `lines that carry a character the server would refuse are dropped and counted`() {
        val bad = listOf("live start caf\u00e9", "live a\nlive b", "live a\tb", "live a\u007f", "live \ud83d\ude00", "audio \u00e9")
        for (l in bad) offer(l)
        assertEquals(0, reporter.waiting())
        assertEquals(bad.size, reporter.droppedInvalid)
        offer("live ok …") // the ellipsis is the one non-ASCII character allowed
        assertEquals(1, reporter.waiting())
    }

    @Test
    fun `lines that look like a URL, an address, a name or a secret are dropped and counted`() {
        val bad = listOf(
            "live start url=https://ha.example.net/x", "live x user@host", "live ip 10.1.2.3", "live v6 fe80::1", "live mac aa:bb:cc:dd:ee:ff",
            "live host ha.example.net", "live Bearer abc", "live BEARER abc", "live tskey-auth-abc", "audio y token=abc", "audio Password: x",
            "audio api_key=1", "audio cookie =x", "live hex 0123456789abcdef01234567", "live " + "A".repeat(40), "live " + "a1+/".repeat(10),
        )
        for (l in bad) offer(l)
        assertEquals(0, reporter.waiting())
        assertEquals(bad.size, reporter.droppedInvalid)
    }

    @Test
    fun `a line over 240 characters is cut with an ellipsis, and a cut that leaves a secret-shaped tail is dropped`() {
        offer("live " + "word ".repeat(60))
        val kept = JournalLines.check("live " + "word ".repeat(60)) as JournalLines.Verdict.Keep
        assertEquals(240, kept.line.length)
        assertTrue(kept.line.endsWith("\u2026"))
        assertEquals(1, reporter.waiting())
        val exactly = "live " + "x ".repeat(117) + "x"
        assertEquals(exactly, (JournalLines.check(exactly) as JournalLines.Verdict.Keep).line, "240 exactly is not cut")
        val over = "$exactly y"
        assertEquals(240, (JournalLines.check(over) as JournalLines.Verdict.Keep).line.length)
        // "xa.bcd1yz" is no host name, but the cut keeps 239 characters and ends right after "xa.bcd": host-shaped, so the line is dropped
        val base = "live " + "ab ".repeat(76)
        assertTrue(JournalLines.check(base + "xa.bcd1").let { it is JournalLines.Verdict.Keep }, "240 characters, no cut, nothing secret-shaped")
        assertEquals(JournalLines.Verdict.Invalid, JournalLines.check(base + "xa.bcd1yz"))
    }

    @Test
    fun `nothing a user could pick out of the privacy list is ever sent`() {
        val risky = listOf(
            "live start url=http://10.1.2.3/stream", "live start rtsp://u:p@host/x", "live fail Bearer abcdef", "audio start tskey-auth-k123",
            "live a@b", "live ws://x", "audio end x://y",
        )
        for (l in risky) offer(l)
        offer("live stop cam=gate…(4) size=big why=hidden secs=12")
        runTasks()
        val sent = items.joinToString("\n")
        for (needle in listOf("://", "@", "Bearer", "tskey-")) assertFalse(needle in sent, needle)
        assertEquals(1, linesOf(parsed().single()).size)
        assertEquals(risky.size, reporter.droppedInvalid)
    }

    // ---- batching ------------------------------------------------------------------------------------------------------

    @Test
    fun `lines wait for a flush five seconds after the first, and one timer serves them all`() {
        offer("live start cam=a…(1) size=big")
        now += 1_000
        offer("live fail cam=a…(1) size=big why=connect")
        assertEquals(1, tasks.size, "one pending flush, not one per line")
        assertEquals(5_000L, tasks.single().first, "five seconds after the first line")
        assertEquals(5_000L, JournalReporter.FLUSH_MS)
        assertTrue(items.isEmpty(), "nothing is sent by offer")
        runTasks()
        assertEquals(1, items.size)
        assertEquals(2, linesOf(parsed().single()).size)
        assertEquals(0, reporter.waiting())
        assertEquals(2, reporter.reportedLines)
        offer("live start cam=a…(1) size=big")
        assertEquals(1, tasks.size, "a later line starts a new timer")
    }

    @Test
    fun `twenty lines flush at once`() {
        repeat(JournalReporter.FLUSH_LINES - 1) { offer("live fail cam=a…(1) size=big why=connect retry=$it next=yes") }
        assertTrue(tasks.all { it.first == JournalReporter.FLUSH_MS })
        offer("live fail cam=a…(1) size=big why=connect retry=19 next=yes")
        assertTrue(tasks.any { it.first == 0L }, "the twentieth line asks for an immediate flush")
        runTasks(0)
        assertEquals(1, items.size)
        assertEquals(20, linesOf(parsed().single()).size)
        runTasks() // the five-second timer finds nothing left
        assertEquals(1, items.size)
    }

    @Test
    fun `live stop and audio end flush at once, a live fail does not`() {
        offer("live start cam=a…(1) size=big")
        offer("live fail cam=a…(1) size=big why=connect retry=1 next=yes")
        assertTrue(tasks.none { it.first == 0L })
        offer("live stop cam=a…(1) size=big why=hidden secs=3")
        assertEquals(1, tasks.count { it.first == 0L })
        offer("live stop cam=a…(1) size=big why=hidden secs=4")
        assertEquals(1, tasks.count { it.first == 0L }, "an immediate flush already waits")
        runTasks(0)
        assertEquals(4, linesOf(parsed().single()).size)
        items.clear()
        offer("audio end answer call=gso-18…(31) up=? down=?")
        assertEquals(1, tasks.count { it.first == 0L })
        runTasks(0)
        assertEquals(1, items.size)
        assertTrue(JournalLines.endsSession("live stop"))
        assertTrue(JournalLines.endsSession("audio end x"))
        assertFalse(JournalLines.endsSession("live stopped"))
        assertFalse(JournalLines.endsSession("audio start x"))
        assertFalse(JournalLines.endsSession("live start x"))
    }

    @Test
    fun `an item holds at most fifty lines, the rest follow in the next item`() {
        repeat(130) { offer("live fail cam=a…(1) size=big why=connect retry=$it next=yes") }
        runTasks()
        val sizes = parsed().map { linesOf(it).size }
        assertEquals(listOf(50, 50, 30), sizes)
        // 130 offered, MAX_PENDING (200) can wait: all are sent, in order
        assertEquals((0 until 130).toList(), parsed().flatMap { o -> linesOf(o).map { it.substringAfter("retry=").substringBefore(' ').toInt() } })
        assertEquals(3, parsed().map { it["id"]!!.jsonPrimitive.content }.toSet().size, "each item has its own id")
    }

    @Test
    fun `the item has the contract's shape and an id the server accepts`() {
        now = t0 + 2_500
        reporter.offer(at(t0, "live start cam=gate…(4) size=big stream=main ttff=840ms"))
        reporter.offer(at(t0 + 1_000, "audio start answer call=gso-18…(31) up=pcmu/8000/20ms down=pcmu/8000/20ms sent=100 updrop=0 recv=99 downdrop=0 mic=low"))
        runTasks()
        assertEquals(
            """{"kind":"journal","id":"id0000000001","app":"0.6.16-alpha","lines":[""" +
                """{"t":1761000000000,"line":"live start cam=gate…(4) size=big stream=main ttff=840ms"},""" +
                """{"t":1761000001000,"line":"audio start answer call=gso-18…(31) up=pcmu/8000/20ms down=pcmu/8000/20ms sent=100 updrop=0 recv=99 downdrop=0 mic=low"}]}""",
            items.single(),
        )
        val o = parsed().single()
        assertEquals(setOf("kind", "id", "app", "lines"), o.keys, "no `v`: that is what an old server rejects harmlessly")
        assertEquals(listOf("t", "line"), o["lines"]!!.jsonArray.first().jsonObject.keys.toList())
    }

    @Test
    fun `the default id is twelve random hex characters`() {
        val seen = mutableSetOf<String>()
        val r = JournalReporter("0.6.16-alpha", { true }, { seen.add(Json.parseToJsonElement(it).jsonObject["id"]!!.jsonPrimitive.content) }, { _, t -> t() })
        repeat(20) { r.offer("live stop cam=a…(1) size=big why=hidden secs=1") }
        assertEquals(20, seen.size)
        for (id in seen) assertTrue(Regex("[0-9a-f]{12}").matches(id), id)
    }

    @Test
    fun `an app version the server would not accept is left out`() {
        val got = mutableListOf<String>()
        val r = JournalReporter("0.6 beta?", { true }, { got.add(it) }, { _, t -> t() })
        r.offer("live stop cam=a…(1) size=big why=hidden secs=1")
        assertFalse("\"app\"" in got.single())
    }

    // ---- bounds ----------------------------------------------------------------------------------------------------------

    @Test
    fun `at most 200 lines wait, the oldest go first and are counted`() {
        repeat(260) { offer("live fail cam=a…(1) size=big why=connect retry=$it next=yes") }
        assertEquals(JournalReporter.MAX_PENDING, reporter.waiting())
        assertEquals(60, reporter.droppedOverflow)
        // the immediate flush that the twentieth line asked for has not run (the executor is busy): what waits is the newest 200
        runTasks()
        val retries = parsed().flatMap { o -> linesOf(o).map { it.substringAfter("retry=").substringBefore(' ').toInt() } }
        assertEquals((60 until 260).toList(), retries)
    }

    @Test
    fun `a window takes at most the budget and counts the rest`() {
        repeat(JournalReporter.MAX_LINES_PER_WINDOW + 25) {
            offer("live fail cam=a…(1) size=big why=connect retry=$it next=yes")
            runTasks()
        }
        assertEquals(JournalReporter.MAX_LINES_PER_WINDOW, items.sumOf { linesOf(Json.parseToJsonElement(it).jsonObject).size })
        assertEquals(25, reporter.droppedRate)
        now += JournalReporter.WINDOW_MS
        offer("live fail cam=a…(1) size=big why=connect retry=0 next=yes")
        runTasks()
        assertEquals(JournalReporter.MAX_LINES_PER_WINDOW + 1, items.sumOf { linesOf(Json.parseToJsonElement(it).jsonObject).size }, "a new window starts")
    }

    // ---- the connlog switch ----------------------------------------------------------------------------------------------

    @Test
    fun `switched off, nothing is kept and nothing is scheduled`() {
        on = false
        repeat(30) { offer("live stop cam=a…(1) size=big why=hidden secs=$it") }
        assertEquals(0, reporter.waiting())
        assertTrue(tasks.isEmpty())
        runTasks()
        assertTrue(items.isEmpty())
        assertEquals(0, reporter.droppedInvalid + reporter.droppedOverflow + reporter.droppedRate)
    }

    @Test
    fun `switched off while lines wait, the flush discards them and later lines report again once it is on`() {
        offer("live start cam=a…(1) size=big")
        on = false
        runTasks()
        assertTrue(items.isEmpty())
        assertEquals(0, reporter.waiting())
        on = true
        offer("live stop cam=a…(1) size=big why=hidden secs=1")
        runTasks()
        assertEquals(1, linesOf(parsed().single()).size)
    }

    @Test
    fun `clear forgets what waits`() {
        offer("live start cam=a…(1) size=big")
        reporter.clear()
        assertEquals(0, reporter.waiting())
        runTasks()
        assertTrue(items.isEmpty())
    }

    // ---- never blocks the writer, never throws into it --------------------------------------------------------------------

    @Test
    fun `offer never calls the sink or waits for anything`() {
        offer("live start cam=a…(1) size=big")
        repeat(30) { offer("live stop cam=a…(1) size=big why=hidden secs=$it") }
        assertTrue(items.isEmpty(), "the sink only runs when the scheduled flush runs, on the connlog executor")
        assertTrue(tasks.isNotEmpty())
    }

    @Test
    fun `a slow sink does not hold up offer from another thread`() {
        val inSink = CountDownLatch(1)
        val release = CountDownLatch(1)
        val onOtherThread: (Long, () -> Unit) -> Unit = { _, task -> Thread(task).apply { isDaemon = true }.start() }
        val real = JournalReporter("0.6.16-alpha", { true }, { inSink.countDown(); release.await(10, TimeUnit.SECONDS) }, onOtherThread)
        real.offer("live stop cam=a…(1) size=big why=hidden secs=1") // flush starts on its own thread and blocks in the sink
        assertTrue(inSink.await(5, TimeUnit.SECONDS))
        val done = CountDownLatch(1)
        Thread { real.offer("live fail cam=a…(1) size=big why=connect retry=1 next=yes"); done.countDown() }.apply { isDaemon = true }.start()
        assertTrue(done.await(2, TimeUnit.SECONDS), "offer must not wait for a flush that is in the sink")
        release.countDown()
    }

    @Test
    fun `a failing sink or schedule is caught, reported once and costs only that item`() {
        sinkThrows = true
        offer("live stop cam=a…(1) size=big why=hidden secs=1")
        runTasks()
        assertEquals(1, faults.size)
        assertTrue(faults.single() is IllegalStateException)
        offer("live stop cam=a…(1) size=big why=hidden secs=2")
        runTasks()
        assertEquals(1, faults.size, "the second failure is not reported again")
        sinkThrows = false
        offer("live stop cam=a…(1) size=big why=hidden secs=3")
        runTasks()
        assertEquals(1, items.size, "the reporter keeps working")

        val boom = mutableListOf<Throwable>()
        val r = JournalReporter("0.6.16-alpha", { true }, { }, { _, _ -> throw java.util.concurrent.RejectedExecutionException() }, { boom.add(it) })
        r.offer("live stop cam=a…(1) size=big why=hidden secs=1")
        r.offer("live stop cam=a…(1) size=big why=hidden secs=1")
        assertEquals(1, boom.size)
        val faulty = JournalReporter("0.6.16-alpha", { throw IllegalStateException("x") }, { }, { _, _ -> }, { throw IllegalArgumentException("in the fault hook") })
        faulty.offer("live stop") // nothing escapes, not even a throwing fault hook
    }

    @Test
    fun `a scheduler that refused once does not stop the reporter for good`() {
        var refuse = true
        val got = mutableListOf<String>()
        val r = JournalReporter("0.6.16-alpha", { true }, { got.add(it) }, { _, t -> if (refuse) throw java.util.concurrent.RejectedExecutionException() else t() })
        r.offer("live start cam=a\u2026(1) size=big")
        refuse = false
        r.offer("live fail cam=a\u2026(1) size=big why=connect retry=1 next=yes")
        assertEquals(1, got.size)
        assertEquals(2, Json.parseToJsonElement(got.single()).jsonObject["lines"]!!.jsonArray.size, "the line that waited goes out with the next")
    }

    @Test
    fun `the fault line is a connlog line, so it is never reported itself`() {
        val line = PushJournalFormat.journalFaultLine(t0, utc, IllegalStateException("secret message"))
        assertEquals("22:40:00.000 connlog journal fault ex=IllegalStateException", line)
        offer(line.substringAfter(' '))
        reporter.offer(line)
        assertEquals(0, reporter.waiting())
        assertEquals(0, reporter.droppedInvalid)
    }

    // ---- the Journal's hook --------------------------------------------------------------------------------------------------

    @Test
    fun `the Journal offers each stored line after writing it, and a failing observer costs nothing`() {
        val store = InMemoryPushJournalStore()
        val journal = PushJournal(store, { t0 }, { utc })
        val seen = mutableListOf<String>()
        journal.lineObserver = { seen.add(it); throw IllegalStateException("observer") }
        assertTrue(journal.add("live stop cam=x…(1) size=big why=hidden secs=1"))
        assertEquals(listOf("live stop cam=x…(1) size=big why=hidden secs=1"), seen)
        assertEquals(1, journal.lines().size)
        journal.lineObserver = null
        assertTrue(journal.add("live stop again"))
        assertEquals(1, seen.size)
        // a line the Journal scrubbed is offered as stored, not as given
        journal.lineObserver = { seen.add(it) }
        journal.add("live start url=https://ha.example.net/x")
        assertEquals("live start url=<url>", seen.last())
        assertNotEquals(0, journal.lines().size)
    }

    // ---- DEV-762 A1: the ring-decision lines ------------------------------------------------------------------------

    /** Lines as `PushJournalFormatTest` / `HouseDndTest` / `HouseRingCoordinatorTest` produce them (the time prefix removed). */
    private val ringLines = listOf(
        "push ring door=01J9EN…(26) call=gso-17…(33) seq=1 t=08:48:23.830 age=160ms known=1 fg=bg fsi=yes chan=ok/4 notif=on dnd=off -> Ring",
        "push test t=08:48:23.830 age=160ms known=1 fg=bg fsi=yes chan=ok/4 notif=on dnd=off -> Quiet",
        "push ignored(unknown_version) t=08:48:23.830 age=160ms -> Silent(ignored)",
        "push dropped kind=ring why=paused",
        "push dropped kind=? why=?",
        "ws event=ringing door=01J9EN…(26) call=gso-17…(33)",
        "ws event=idle door=01J9EN…(26)",
        "ui Ring door=01J9EN…(26) call=gso-17…(33) post=ring+fsi notif=on ringchan=on high=yes statuschan=on fsi=yes stop=no",
        "ui Dismiss door=01J9EN…(26) call=gso-17…(33) post=answered_elsewhere stop=yes",
        "hold switched door=01J9EN…(26) call=gso-17…(33) ms=30000",
        "hold resume-refused call=gso-17…(33) result=not_held",
        "hold focus-lost n=2",
        "house ring call=hso-17…(10)",
        "house dnd n=1",
        "frames v=2 call=hso-17…(10) codec=l16 rate=16000 secs=42 sent=1050 dropped=0 recv=1049 underruns=1 maxgap=120ms p95gap=60ms qmin=1 qmax=5",
        "audio dev call=hso-17…(10) first=40ms uppeak=-12 dnpeak=-9 upclip=0 dnclip=0 readmax=45ms aec=on ns=on route=speaker why=none mark=none fail=none",
        "net connect via=direct",
    )

    @Test
    fun `the ring-decision lines the app writes are reported`() {
        for (l in ringLines) assertTrue(JournalLines.check(l) is JournalLines.Verdict.Keep, l)
        for (l in ringLines) offer(l)
        assertEquals(ringLines.size, reporter.waiting())
        assertEquals(0, reporter.droppedInvalid)
        runTasks()
        assertEquals(ringLines, linesOf(parsed().single()))
    }

    @Test
    fun `a ring-decision line that looks secret is still refused and counted`() {
        val refused = listOf(
            "push ring url=https://ha.example.net/x",
            "push ring door=x token=abc123",
            "ws event=ringing host=ha.example.net",
            "ws connect 192.168.1.6",
            "ui Ring owner=alice@example.org",
            "hold switched call=" + "a1b2c3d4e5f6".repeat(2),
            "house ring key=" + "Zz9_".repeat(11),
            "net connect fd00:1234:5678:9abc::1",
            "net connect via=tskey-auth-abc",
            "frames Authorization: Bearer abc",
            "ui Ring caf\u00e9",
            "push ring\nlive start",
        )
        for (l in refused) assertEquals(JournalLines.Verdict.Invalid, JournalLines.check(l), l)
        for (l in refused) offer(l)
        assertEquals(0, reporter.waiting())
        assertEquals(refused.size, reporter.droppedInvalid)
    }
}
