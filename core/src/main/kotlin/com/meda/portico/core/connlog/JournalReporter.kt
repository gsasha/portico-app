package com.meda.portico.core.connlog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reports the app's `live` (live video) and `audio` (call audio) Journal lines to Home Assistant (DEV-713 / DEV-705), through the
 * connection log's own queue, sender and socket: every Journal line is [offer]ed here; the ones whose first word is `live` or
 * `audio` are batched into `{"kind":"journal","id","app","lines":[{"t","line"}]}` items and handed to [sink] (the connlog queue's
 * `add`), which the existing [ConnLogSender] sends in `portico/connlog/report`. No second sender, socket or command.
 *
 * - **Never blocks the Journal's writer.** [offer] does cheap in-memory work under one short lock and, at most, asks [schedule] to run
 *   a flush later; it never calls [sink] (disk and JSON work), and the flush runs on the connlog executor, outside that lock.
 * - **Same rules as the server.** A line is kept only if portico-ha's `journal_line_ok` would keep it ([JournalLines]); one it would
 *   drop is dropped HERE and counted ([droppedInvalid]), so nothing the server would refuse is ever put on the wire.
 * - **Batched.** A flush happens [FLUSH_MS] after the first waiting line, or at once at [FLUSH_LINES] waiting lines, or at once when
 *   the line closes a session (`live stop`, `audio end`); flushes are serialised (one at a time) and a flush sends the lines in items
 *   of at most [MAX_ITEM_LINES]. Each item has a random 12-hex `id`, the server's dedup key.
 * - **Bounded.** At most [MAX_PENDING] lines wait (the oldest go first, counted in [droppedOverflow]) and at most
 *   [MAX_LINES_PER_WINDOW] are taken per [WINDOW_MS] (the rest counted in [droppedRate]), well under the server's 3000 per 10 minutes.
 * - **Follows the connlog switch.** [enabled] is the connection log's "Send connection logs to Home Assistant": off, nothing is
 *   buffered and a waiting buffer is discarded at its flush.
 * - **TOTAL.** Nothing here throws into the Journal. A failure is caught and [onFault] hears the FIRST one only.
 */
class JournalReporter(
    private val appVersion: String,
    private val enabled: () -> Boolean,
    private val sink: (String) -> Unit,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val onFault: (Throwable) -> Unit = {},
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val zone: () -> TimeZone = TimeZone::getDefault,
    private val newId: () -> String = { UUID.randomUUID().toString().replace("-", "").take(12) },
) {
    private class Entry(val t: Long, val line: String)

    private val lock = Any()
    private val flushLock = Any()
    private val pending = ArrayDeque<Entry>()
    private var timerScheduled = false
    private var urgentScheduled = false
    private var windowStart = 0L
    private var windowLines = 0
    private val faulted = AtomicBoolean(false)

    private val invalid = AtomicInteger()
    private val overflow = AtomicInteger()
    private val rated = AtomicInteger()
    private val reported = AtomicInteger()

    /** Lines of ours (live/audio) the server's rules would have refused (a character, a URL, an address, a token...). */
    val droppedInvalid: Int get() = invalid.get()

    /** Lines dropped because more than [MAX_PENDING] were waiting. */
    val droppedOverflow: Int get() = overflow.get()

    /** Lines dropped by the per-window budget. */
    val droppedRate: Int get() = rated.get()

    /** Lines handed to the sink since this process started. */
    val reportedLines: Int get() = reported.get()

    fun waiting(): Int = synchronized(lock) { pending.size }

    /** One Journal line as the Journal wrote it (`HH:mm:ss.SSS live start ...`). Non-blocking and total. */
    fun offer(raw: String) {
        try {
            if (!enabled()) return
            val now = clockMs()
            val stamped = JournalLines.stamp(raw, now, zone())
            val line = when (val v = JournalLines.check(stamped.body)) {
                JournalLines.Verdict.Ignored -> return
                JournalLines.Verdict.Invalid -> { invalid.incrementAndGet(); return }
                is JournalLines.Verdict.Keep -> v.line
            }
            var delay = -1L
            var urgent = false
            synchronized(lock) {
                if (now - windowStart >= WINDOW_MS || now < windowStart) { windowStart = now; windowLines = 0 }
                if (windowLines >= MAX_LINES_PER_WINDOW) { rated.incrementAndGet(); return }
                windowLines++
                pending.addLast(Entry(stamped.t, line))
                if (pending.size > MAX_PENDING) { pending.removeFirst(); overflow.incrementAndGet() }
                if (pending.size >= FLUSH_LINES || JournalLines.endsSession(line)) {
                    if (!urgentScheduled) { urgentScheduled = true; delay = 0; urgent = true }
                } else if (!timerScheduled && !urgentScheduled) {
                    timerScheduled = true
                    delay = FLUSH_MS
                }
            }
            if (delay >= 0) {
                try {
                    schedule(delay) { run(urgent) }
                } catch (e: Throwable) {
                    synchronized(lock) { if (urgent) urgentScheduled = false else timerScheduled = false } // a later line may try again
                    throw e
                }
            }
        } catch (e: Throwable) {
            fault(e)
        }
    }

    /** Forget everything waiting (the connlog switch went off). */
    fun clear() {
        synchronized(lock) { pending.clear() }
    }

    /**
     * Hand every waiting line to the sink NOW, on the calling thread (DEV-762 A2: the crash handler, before the process dies; today
     * the lines wait up to [FLUSH_MS] in memory). Total, like [offer]; the connlog switch still decides (off: the buffer is discarded).
     */
    fun flushNow() = flush()

    private fun run(urgent: Boolean) {
        synchronized(lock) { if (urgent) urgentScheduled = false else timerScheduled = false }
        flush()
    }

    private fun flush() {
        try {
            synchronized(flushLock) {
                if (!enabled()) { clear(); return }
                while (true) {
                    val chunk = synchronized(lock) {
                        val n = minOf(MAX_ITEM_LINES, pending.size)
                        List(n) { pending.removeFirst() }
                    }
                    if (chunk.isEmpty()) break
                    try {
                        sink(render(chunk))
                        reported.addAndGet(chunk.size)
                    } catch (e: Throwable) {
                        fault(e) // this item is lost; the rest still goes
                    }
                }
            }
        } catch (e: Throwable) {
            fault(e)
        }
    }

    private fun render(chunk: List<Entry>): String = buildJsonObject {
        put("kind", "journal")
        put("id", newId())
        if (APP.matches(appVersion)) put("app", appVersion)
        put(
            "lines",
            JsonArray(
                chunk.map {
                    buildJsonObject {
                        put("t", it.t)
                        put("line", it.line)
                    }
                },
            ),
        )
    }.toString()

    private fun fault(e: Throwable) {
        if (!faulted.compareAndSet(false, true)) return
        try {
            onFault(e)
        } catch (_: Throwable) {
            // a failing report of a failure is not reported
        }
    }

    companion object {
        /** A flush this long after the first waiting line. */
        const val FLUSH_MS = 5_000L

        /** ... or at once when this many lines wait. */
        const val FLUSH_LINES = 20

        /** The server keeps at most 50 lines of an item. */
        const val MAX_ITEM_LINES = 50

        /** Lines waiting for a flush; the oldest are dropped past this. */
        const val MAX_PENDING = 200

        /** A budget per window, under the server's 3000 per user per ten minutes. */
        const val MAX_LINES_PER_WINDOW = 1000
        const val WINDOW_MS = 10L * 60 * 1000

        private val APP = Regex("[A-Za-z0-9._+-]{1,32}")
    }
}

/**
 * The pure rules for one Journal line on its way to HA: the time prefix back to an epoch, and portico-ha's `journal_line_ok` (README
 * 'connlog journal'): a line of 1..240 printable-ASCII characters (and U+2026, which `Redact.prefix` writes), whose first word is `live`
 * or `audio`, and that carries no URL, address, `@`, `Bearer`, `tskey-`, `token=` style key, host name or long hex / base64 run.
 */
object JournalLines {
    const val MAX_LINE = 240
    private const val ELLIPSIS = '…'
    private const val DAY_MS = 86_400_000L
    private const val MAX_T = 10_000_000_000_000L

    /** A line that claims to be from further ahead than this was last night's (a midnight rollover). */
    private const val FUTURE_SLACK_MS = 2_000L

    private val PREFIX = Regex("^(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3}) ")
    private val CHARS = Regex("[\\u0020-\\u007e\\u2026]+")
    private val SECRETS = listOf(
        Regex("://|@|::|tskey-|bearer", RegexOption.IGNORE_CASE),
        Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b"),
        Regex("(?:[0-9A-Fa-f]{1,4}:){3,}"),
        Regex("[A-Za-z0-9-]+\\.(?:[A-Za-z0-9-]+\\.)*[A-Za-z]{2,}\\b"),
        Regex("[0-9A-Fa-f]{24,}"),
        Regex("[A-Za-z0-9+/_=-]{40,}"),
        Regex("(?:token|passw(?:or)?d|secret|api_?key|authorization|cookie)\\s*[=:]", RegexOption.IGNORE_CASE),
    )

    class Stamped(val t: Long, val body: String)

    sealed interface Verdict {
        /** Not a live or audio line: nothing to report, nothing to count. */
        data object Ignored : Verdict

        /** A live or audio line the server would refuse: dropped, and counted. */
        data object Invalid : Verdict
        data class Keep(val line: String) : Verdict
    }

    /**
     * Split the Journal's `HH:mm:ss.SSS ` prefix (the phone's local time of day, in [zone]) from [raw] and turn it into epoch
     * milliseconds on the local day of [nowMs]; a time further than [FUTURE_SLACK_MS] ahead of [nowMs] belongs to yesterday (the
     * line was written just before midnight). No (valid) prefix: [nowMs] and the whole text.
     */
    fun stamp(raw: String, nowMs: Long, zone: TimeZone): Stamped {
        val m = PREFIX.find(raw) ?: return Stamped(nowMs.coerceIn(0, MAX_T), raw.trim())
        val (h, mi, s, ms) = m.destructured
        if (h.toInt() > 23 || mi.toInt() > 59 || s.toInt() > 59) return Stamped(nowMs.coerceIn(0, MAX_T), raw.trim())
        val tod = ((h.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + ms.toLong()
        val offset = zone.getOffset(nowMs)
        val dayStart = Math.floorDiv(nowMs + offset, DAY_MS) * DAY_MS
        var t = dayStart + tod - offset
        if (t > nowMs + FUTURE_SLACK_MS) t -= DAY_MS
        return Stamped(t.coerceIn(0, MAX_T), raw.substring(m.value.length).trim())
    }

    /** Whether [body] (a line without its time) is reported, ignored or dropped. */
    fun check(body: String): Verdict {
        if (body.substringBefore(' ') !in WORDS) return Verdict.Ignored
        if (!CHARS.matches(body) || secret(body)) return Verdict.Invalid
        val line = if (body.length > MAX_LINE) body.substring(0, MAX_LINE - 1) + ELLIPSIS else body
        if (line !== body && secret(line)) return Verdict.Invalid // a cut can leave a host-like or token-like tail
        return Verdict.Keep(line)
    }

    /** The line that closes a live or call-audio session: it makes a flush at once. */
    fun endsSession(line: String): Boolean = line == "live stop" || line.startsWith("live stop ") || line == "audio end" || line.startsWith("audio end ") || line.startsWith("ui talk-end ")

    private fun secret(text: String): Boolean = SECRETS.any { it.containsMatchIn(text) }

    /** The same words as portico-ha's `JOURNAL_WORDS` (DEV-762 A1): live/audio, and the ring-decision lines of `PushJournalFormat`. */
    private val WORDS = setOf("live", "audio", "push", "ws", "ui", "hold", "house", "frames", "net", "layout", "wall")
}
