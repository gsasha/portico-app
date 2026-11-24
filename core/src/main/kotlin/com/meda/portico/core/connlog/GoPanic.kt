package com.meda.portico.core.connlog

/** The app's own logcat tail for tag `GoLog` (`logcat -d -v epoch -s GoLog`), one entry per line, or null. Total: never throws. */
fun interface GoLogSource {
    fun lines(): List<String>?
}

/**
 * DEV-762 A4 (case C5): the Go panic of a `crash_native` exit, read from the app's own `GoLog` logcat entries. PURE.
 *
 * Privacy, by construction. Go's panic text is `panic: <message>` (the message may carry addresses, hosts, paths, peer names), then
 * `goroutine N [state]:`, then pairs of lines: `pkg/path.(*T).Func(0xc000123, {...})` and `\t/abs/path/file.go:123 +0x1d4`.
 * - The panic MESSAGE and every other line (file paths, `+0x` offsets, `[signal ...]`, `exit status`) are never copied: a line is used
 *   only if it matches the strict function-frame shape, and the record carries the FUNCTION NAME rebuilt from captured parts, with the
 *   import path cut to its last segment and the receiver star removed. Argument lists are matched (hex, `...`, `{...}`) and thrown away.
 * - A frame line whose parts do not fit the closed character set is DROPPED, never cleaned.
 * - Each kept line also passes the crash record's host / secret filters. At most [MAX_LINES] lines of [MAX_LINE] characters.
 * - Only entries stamped within [WINDOW_BEFORE_MS] before / [WINDOW_AFTER_MS] after the exit are read: logd's buffer outlives the
 *   process, and an older panic must not be pinned on a later exit.
 */
object GoPanic {
    const val MAX_LINES = ExitRecord.MAX_TRACE
    const val MAX_LINE = ExitRecord.MAX_LINE
    const val WINDOW_BEFORE_MS = 60_000L
    const val WINDOW_AFTER_MS = 2_000L
    /** The fixed first line of a Go trace: the record says a Go panic was seen, and says nothing the panic said. */
    const val MARK = "go panic"

    // `1762000000.123  1234  1250 E GoLog   : text` (threadtime with -v epoch); the text keeps its own leading tab / spaces.
    private val ENTRY = Regex("^\\s*(\\d{9,11})\\.(\\d{3})\\s+\\d+\\s+\\d+\\s+[VDIWEF]\\s+GoLog\\s*:\\s?(.*)$")
    private val PANIC_START = Regex("^(?:panic:|fatal error:|SIG[A-Z]+: )")
    private val GOROUTINE = Regex("goroutine \\d{1,9} \\[[a-z0-9 ,]{1,40}\\]:")
    private const val ARGS = "[0-9a-fx,. {}]{0,200}|\\.\\.\\."
    // pkg/path/seg.(*Type).Func(args) | seg.Func.func1.2(args); captured: last path segment + name.
    private val FRAME = Regex(
        "(?:[A-Za-z0-9_.%-]{1,80}/){0,10}([A-Za-z0-9_-]{1,60})\\.((?:\\(\\*?[A-Za-z0-9_]{1,60}(?:\\[\\.\\.\\.\\])?\\)\\.)?[A-Za-z0-9_]{1,60}" +
            "(?:\\[\\.\\.\\.\\])?(?:\\.func\\d{1,3}(?:\\.\\d{1,3})*)*)\\((?:$ARGS)\\)",
    )
    private val CREATED = Regex("created by ((?:[A-Za-z0-9_.%-]{1,80}/){0,10}[A-Za-z0-9_-]{1,60}\\.[A-Za-z0-9_.()*\\[\\]-]{1,120})(?: in goroutine \\d{1,9})?")

    private class Entry(val ms: Long, val text: String)

    private fun entries(lines: List<String>): List<Entry> = lines.mapNotNull { l ->
        val m = ENTRY.matchEntire(l.take(2_000)) ?: return@mapNotNull null
        Entry(m.groupValues[1].toLong() * 1000 + m.groupValues[2].toLong(), m.groupValues[3])
    }

    /** The function name of one frame line, rebuilt as `seg.(Type).Func`, or null if the line is not exactly a frame. */
    fun frameOf(raw: String): String? {
        val l = raw.trimEnd()
        if (l.isEmpty() || l[0] == '\t' || l[0] == ' ') return null // file lines are indented; so is anything else we do not know
        val m = FRAME.matchEntire(l)
        val name = if (m != null) {
            val pkg = m.groupValues[1]
            val fn = m.groupValues[2].replace("(*", "(").replace("[...]", "")
            "$pkg.$fn"
        } else {
            val c = CREATED.matchEntire(l) ?: return null
            val full = c.groupValues[1].substringAfterLast('/').replace("(*", "(").replace("[...]", "").replace("*", "")
            "created by $full"
        }
        return if (name.length <= MAX_LINE && CrashRecord.textOk(name, MAX_LINE) && Regex("[A-Za-z0-9_.:()<> -]+").matches(name)) name else null
    }

    /**
     * The Go trace for an exit at [exitMs]: [MARK] then the frame names of the LAST panic block inside the time window, or empty
     * when none (no panic seen, unreadable stamps, nothing in the window). Total.
     */
    fun traceOf(logLines: List<String>?, exitMs: Long): List<String> = try {
        val inWindow = entries(logLines ?: emptyList()).filter { it.ms in (exitMs - WINDOW_BEFORE_MS)..(exitMs + WINDOW_AFTER_MS) }
        // the last block starts at the last panic marker; it counts only if a goroutine header follows
        val start = inWindow.indexOfLast { PANIC_START.containsMatchIn(it.text.trim()) }
        val out = ArrayList<String>()
        if (start >= 0 && inWindow.drop(start).any { GOROUTINE.matches(it.text.trim()) }) {
            out.add(MARK)
            for (e in inWindow.drop(start)) {
                if (out.size >= MAX_LINES) break
                frameOf(e.text)?.let { out.add(it) }
            }
        }
        out
    } catch (_: Throwable) {
        emptyList()
    }
}
