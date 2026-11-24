package com.meda.portico.core.connlog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `crash` record of DEV-762 A2 (case C4): `{kind, id, app, sdk, t, up, model, rel, thread, chain[<=8], frames[<=12]}`, the
 * key set and limits of portico-ha's `sanitize_diag`. PURE: everything comes in as arguments.
 *
 * Privacy: the exception MESSAGE (and the cause message, and `toString()`) is never read. Only class names (`chain`), method names
 * with line numbers of OUR OWN packages (`frames`) and the thread name leave, each checked against the server's closed shapes and
 * its secret / host rules; anything that does not fit is dropped here, so nothing the server would refuse is sent.
 */
object CrashRecord {
    const val MAX_CHAIN = 8
    const val MAX_FRAMES = 8 // the design: the top 8 frames of our packages (the server keeps up to 12)
    const val OWN_PREFIX = "com.meda.portico."

    private val WORD = Regex("[A-Za-z0-9_.\$:()<> -]{1,64}")
    private val JAVA_NAME = Regex("[A-Za-z_\$][A-Za-z0-9_\$]{0,59}(?:\\.[A-Za-z_\$<][A-Za-z0-9_\$>]{0,59}){0,12}")
    private val FRAME = Regex("[A-Za-z_\$][A-Za-z0-9_\$]{0,59}(?:\\.[A-Za-z_\$<][A-Za-z0-9_\$>]{0,59}){1,12}(?::\\d{1,6})?")
    private val APP = Regex("[A-Za-z0-9._+-]{1,32}")
    private val ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val BAD_METHOD_CHARS = Regex("[^A-Za-z0-9_\$<>]")
    private const val MAX_STR = 240
    private const val MAX_NUM = 10_000_000_000_000L

    // The server's `_SECRETS` without the host-name rule (every Java frame is dotted), and its TLD-list host rule.
    private val SECRETS = listOf(
        Regex("://|@|::|tskey-|bearer", RegexOption.IGNORE_CASE),
        Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b"),
        Regex("(?:[0-9A-Fa-f]{1,4}:){3,}"),
        Regex("[0-9A-Fa-f]{24,}"),
        Regex("[A-Za-z0-9+/_=-]{40,}"),
        Regex("(?:token|passw(?:or)?d|secret|api_?key|authorization|cookie)\\s*[=:]", RegexOption.IGNORE_CASE),
    )
    private val HOSTISH = Regex(
        "[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.(?:com|net|org|io|dev|app|local|lan|home|internal|arpa|ts|il|ru|edu|gov|co|me|info|xyz|cloud|ai|us|uk|de|fr)(?![\\w.\$])",
        RegexOption.IGNORE_CASE,
    )

    internal fun textOk(text: String, limit: Int = MAX_STR) =
        text.isNotEmpty() && text.length <= limit && !HOSTISH.containsMatchIn(text) && SECRETS.none { it.containsMatchIn(text) }

    /** The exception class names, outermost first, at most [MAX_CHAIN]; a cycle of causes ends the walk. */
    fun chainOf(t: Throwable): List<String> {
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        val out = ArrayList<String>()
        var cur: Throwable? = t
        while (cur != null && out.size < MAX_CHAIN && seen.put(cur, true) == null) {
            val name = cur.javaClass.name
            if (JAVA_NAME.matches(name) && textOk(name)) out.add(name)
            cur = cur.cause
        }
        return out
    }

    /** `Class.method:line` for the first [MAX_FRAMES] frames of our own packages (the deepest cause that has any, else none). */
    fun framesOf(t: Throwable): List<String> {
        val causes = ArrayList<Throwable>()
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        var cur: Throwable? = t
        while (cur != null && causes.size < 16 && seen.put(cur, true) == null) { causes.add(cur); cur = cur.cause }
        for (c in causes.asReversed()) {
            val frames = ArrayList<String>()
            for (e in c.stackTrace) {
                if (frames.size >= MAX_FRAMES) break
                val f = frame(e) ?: continue
                frames.add(f)
            }
            if (frames.isNotEmpty()) return frames
        }
        return emptyList()
    }

    private fun frame(e: StackTraceElement): String? {
        val cls = e.className
        if (!cls.startsWith(OWN_PREFIX)) return null
        val method = e.methodName.replace(BAD_METHOD_CHARS, "_")
        val line = if (e.lineNumber in 1..999_999) ":" + e.lineNumber else ""
        val f = "$cls.$method$line"
        return f.takeIf { FRAME.matches(it) && textOk(it) }
    }

    internal fun word(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw.replace(Regex("[^A-Za-z0-9_.\$:()<> -]"), "_").take(64)
        return cleaned.takeIf { WORD.matches(it) && textOk(it, 64) }
    }

    /**
     * The compact JSON of the crash record, or null when there is nothing to say (no class name and no frame survives, or [id] / [nowMs]
     * are unusable): the server refuses such a record, so none is queued.
     */
    fun build(
        t: Throwable,
        threadName: String?,
        id: String,
        appVersion: String,
        sdk: Int,
        nowMs: Long,
        upMs: Long,
        model: String?,
        release: String?,
    ): String? {
        if (!ID.matches(id) || nowMs !in 0..MAX_NUM) return null
        val chain = chainOf(t)
        val frames = framesOf(t)
        if (chain.isEmpty() && frames.isEmpty()) return null
        return buildJsonObject {
            put("kind", "crash")
            put("id", id)
            if (APP.matches(appVersion)) put("app", appVersion)
            if (sdk >= 0) put("sdk", sdk)
            put("t", nowMs)
            if (upMs in 0..MAX_NUM) put("up", upMs)
            word(model)?.let { put("model", it) }
            word(release)?.let { put("rel", it) }
            word(threadName)?.let { put("thread", it) }
            put("chain", JsonArray(chain.map(::JsonPrimitive)))
            put("frames", JsonArray(frames.map(::JsonPrimitive)))
        }.toString()
    }
}

/** At most [max] crash records per [windowMs], remembered in [read]/[write] (a crash loop must not fill the queue). Total. */
class CrashRateLimit(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val max: Int = 5,
    private val windowMs: Long = 10L * 60 * 1000,
) {
    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean = try {
        val recent = (read() ?: "").split(',').mapNotNull { it.toLongOrNull() }.filter { it in (nowMs - windowMs)..nowMs }
        if (recent.size >= max) false else { write((recent + nowMs).joinToString(",")); true }
    } catch (_: Throwable) {
        true // a broken counter must not hide a crash
    }
}

/**
 * The uncaught-exception handler (DEV-762 A2). In order, inside [budgetMs] and a `try`: (1) [flushJournal] hands the Journal reporter's
 * waiting lines to the persisted queue, (2) the crash record is built and [enqueueDurable] puts it in the same queue, on disk. Then
 * ALWAYS [previous] runs (the system crash dialog and the normal kill), whatever happened above. The record is sent by the existing
 * sender on the next connect. Off ([enabled] false): only [previous] runs.
 */
class CrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val enabled: () -> Boolean,
    private val flushJournal: () -> Unit,
    private val buildRecord: (Throwable, Thread) -> String?,
    private val enqueueDurable: (String) -> Unit,
    private val allow: () -> Boolean = { true },
    private val onFault: (Throwable) -> Unit = {},
    private val budgetMs: Long = BUDGET_MS,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, e: Throwable) {
        try {
            if (enabled()) {
                val work = Thread({
                    try {
                        try { flushJournal() } catch (x: Throwable) { onFault(x) } // a failed flush must not cost the record
                        if (allow()) buildRecord(e, thread)?.let(enqueueDurable)
                    } catch (x: Throwable) {
                        try { onFault(x) } catch (_: Throwable) {}
                    }
                }, "portico-crash-report")
                work.isDaemon = true
                work.start()
                work.join(budgetMs)
            }
        } catch (_: Throwable) {
            // the process dies as before
        }
        previous?.uncaughtException(thread, e)
    }

    companion object {
        const val BUDGET_MS = 200L

        /** [CrashHandler] around [current], unless [current] already is one (installing twice is safe). */
        fun wrapping(current: Thread.UncaughtExceptionHandler?, make: (Thread.UncaughtExceptionHandler?) -> CrashHandler): Thread.UncaughtExceptionHandler =
            if (current is CrashHandler) current else make(current)
    }
}
