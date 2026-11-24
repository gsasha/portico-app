package com.meda.portico.core.connlog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One platform exit record (`ApplicationExitInfo`), copied into plain values so JVM tests need no Android. */
class PlatformExit(
    val reason: Int,
    val importance: Int,
    val timestampMs: Long,
    /** The platform's trace stream as text lines, or null when it provides none. Read lazily: only for a record we send. */
    val traceLines: () -> List<String>? = { null },
)

/** The platform call behind a tiny interface (API 30+ `getHistoricalProcessExitReasons`); tests fake it. Total: never throws. */
fun interface ExitSource {
    fun recent(max: Int): List<PlatformExit>
}

/**
 * The `exit` record of DEV-762 A3 (cases C5 and C6): `{kind, id, app, sdk, t, up, age, model, rel, reason, importance, phase, trace[<=30]}`,
 * the key set and limits of portico-ha's `sanitize_diag`. PURE.
 *
 * Privacy: reason / importance / phase are words from closed lists. The trace is the platform's own, and only lines of the strict Java
 * frame shape `at pkg.Class.method(File.java:12)` survive; ANR and native traces also carry addresses, paths and library names, and
 * a line that does not fit is DROPPED, never cleaned. Each surviving line also passes the crash record's host / secret filters.
 */
object ExitRecord {
    const val MAX_TRACE = 30
    const val MAX_LINE = 240
    const val REASON_STARTUP_DIED = "startup_died"
    const val PHASE_STARTUP = "startup"

    // ApplicationExitInfo.REASON_* values, spelled out so the core module needs no Android classes.
    const val R_EXIT_SELF = 1
    const val R_SIGNALED = 2
    const val R_LOW_MEMORY = 3
    const val R_CRASH = 4
    const val R_CRASH_NATIVE = 5
    const val R_ANR = 6
    const val R_INITIALIZATION_FAILURE = 7
    const val R_PERMISSION_CHANGE = 8
    const val R_EXCESSIVE_RESOURCE_USAGE = 9
    const val R_USER_REQUESTED = 10
    const val R_USER_STOPPED = 11
    const val R_DEPENDENCY_DIED = 12
    const val R_OTHER = 13
    const val R_FREEZER = 14
    const val R_PACKAGE_STATE_CHANGE = 15
    const val R_PACKAGE_UPDATED = 16

    private val ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val APP = Regex("[A-Za-z0-9._+-]{1,32}")
    private const val MAX_NUM = 10_000_000_000_000L
    private val TRACE_CHARS = Regex("[A-Za-z0-9_.\$:()<> -]+")
    private val TRACE_LINE = Regex(
        "at [A-Za-z_\$][A-Za-z0-9_\$]{0,59}(?:\\.[A-Za-z_\$<][A-Za-z0-9_\$>]{0,59}){1,12}" +
            "\\((?:[A-Za-z_\$][A-Za-z0-9_\$]{0,59}\\.(?:java|kt)(?::\\d{1,6})?|Native Method|Unknown Source)\\)",
    )

    fun reasonWord(reason: Int): String = when (reason) {
        R_EXIT_SELF -> "exit_self"
        R_SIGNALED -> "signaled"
        R_LOW_MEMORY -> "low_memory"
        R_CRASH -> "crash"
        R_CRASH_NATIVE -> "crash_native"
        R_ANR -> "anr"
        R_INITIALIZATION_FAILURE -> "init_failure"
        R_PERMISSION_CHANGE -> "permission_change"
        R_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource"
        R_USER_REQUESTED -> "user_requested"
        R_USER_STOPPED -> "user_stopped"
        R_DEPENDENCY_DIED -> "dependency_died"
        R_OTHER -> "other"
        R_FREEZER -> "freezer"
        R_PACKAGE_STATE_CHANGE -> "package_state"
        R_PACKAGE_UPDATED -> "package_updated"
        else -> "unknown"
    }

    /** RunningAppProcessInfo.IMPORTANCE_* as a word (the thresholds are the platform's own values). */
    fun importanceWord(importance: Int): String = when {
        importance <= 100 -> "foreground"
        importance <= 125 -> "foreground_service"
        importance <= 150 -> "top_sleeping"
        importance <= 200 -> "visible"
        importance <= 230 -> "perceptible"
        importance <= 270 -> "cant_save_state"
        importance <= 300 -> "service_process" // not the bare word: HomeResourcesTest greps for it
        importance <= 400 -> "cached"
        else -> "gone"
    }

    /** Something went wrong: not the app quitting by itself, the user stopping it, or an update / permission / package change. */
    fun isAbnormal(reason: Int): Boolean = when (reason) {
        R_EXIT_SELF, R_USER_REQUESTED, R_USER_STOPPED, R_PERMISSION_CHANGE, R_PACKAGE_STATE_CHANGE, R_PACKAGE_UPDATED -> false
        else -> true
    }

    /** A reason that, with an unfinished start-up marker, means the process died while starting. */
    fun isStartupFatal(reason: Int): Boolean =
        reason == R_CRASH || reason == R_CRASH_NATIVE || reason == R_INITIALIZATION_FAILURE || reason == R_ANR

    /** Only the lines of the strict shape, trimmed, at most [MAX_TRACE]; everything else is dropped, never sanitised. */
    fun traceOf(lines: List<String>?): List<String> {
        if (lines == null) return emptyList()
        val out = ArrayList<String>()
        for (raw in lines) {
            if (out.size >= MAX_TRACE) break
            val l = raw.trim()
            if (l.length <= MAX_LINE && TRACE_LINE.matches(l) && TRACE_CHARS.matches(l) && CrashRecord.textOk(l, MAX_LINE)) out.add(l)
        }
        return out
    }

    /** Reads at most [maxChars] of a trace into lines (bounded: a trace can be megabytes). */
    fun linesOf(text: String, maxChars: Int = 65_536): List<String> = text.take(maxChars).split('\n')

    /** The compact JSON, or null when [id], [nowMs] or [reason] is unusable (the server refuses such a record). */
    fun build(
        reason: String,
        importance: String?,
        phase: String?,
        trace: List<String>,
        id: String,
        appVersion: String,
        sdk: Int,
        tMs: Long,
        ageMs: Long,
        upMs: Long,
        model: String?,
        release: String?,
    ): String? {
        val r = CrashRecord.word(reason) ?: return null
        if (!ID.matches(id) || tMs !in 0..MAX_NUM) return null
        return buildJsonObject {
            put("kind", "exit")
            put("id", id)
            if (APP.matches(appVersion)) put("app", appVersion)
            if (sdk >= 0) put("sdk", sdk)
            put("t", tMs)
            if (upMs in 0..MAX_NUM) put("up", upMs)
            if (ageMs in 0..MAX_NUM) put("age", ageMs)
            CrashRecord.word(model)?.let { put("model", it) }
            CrashRecord.word(release)?.let { put("rel", it) }
            put("reason", r)
            CrashRecord.word(importance)?.let { put("importance", it) }
            CrashRecord.word(phase)?.let { put("phase", it) }
            if (trace.isNotEmpty()) put("trace", JsonArray(trace.take(MAX_TRACE).map(::JsonPrimitive)))
        }.toString()
    }
}

/** The start-up marker of C6, kept in [read]/[write] (a prefs string): the set time, or empty. Total. */
class StartupMarker(private val read: () -> String?, private val write: (String?) -> Unit) {
    fun set(nowMs: Long) { try { write(nowMs.toString()) } catch (_: Throwable) {} }
    fun clear() { try { if (read() != null) write(null) } catch (_: Throwable) {} }
    fun pendingSince(): Long? = try { read()?.toLongOrNull() } catch (_: Throwable) { null }
}

/**
 * On app start (once, off the main thread) reports each NEW abnormal platform exit as an `exit` record, plus `startup_died` for an
 * unfinished [StartupMarker]. Never throws; the cursor only moves forward, so nothing is sent twice. Off ([enabled] false): the
 * cursor and marker still advance, so a record is not held back and sent after the user switches diagnostics on.
 *
 * - [lastCrashMs]: when A2's handler last queued a `crash` record; a platform CRASH within [CRASH_DEDUP_MS] after it is that crash.
 * - On API < 30 ([source] empty) a marker alone yields `startup_died`; with platform data the marker only tags the fatal exits
 *   `phase=startup`, and `startup_died` is sent only if no platform exit at all explains the death (a push-only process the
 *   system culled leaves an exit record, a death before any record exists does not).
 */
class ExitReporter(
    private val source: ExitSource,
    private val readCursor: () -> Long?,
    private val writeCursor: (Long) -> Unit,
    private val enabled: () -> Boolean,
    private val lastCrashMs: () -> Long?,
    private val enqueue: (String) -> Unit,
    private val newId: () -> String,
    private val now: () -> Long,
    private val upMs: () -> Long,
    private val appVersion: String,
    private val sdk: Int,
    private val model: String?,
    private val release: String?,
    /** DEV-762 A4: the app's own `GoLog` logcat tail, read only for a `crash_native` exit that is being sent (switch on). */
    private val goLog: GoLogSource? = null,
    private val onFault: (Throwable) -> Unit = {},
) {
    /** [markerSince]: the marker the PREVIOUS process left (read and replaced by the caller before this runs), or null. */
    fun run(markerSince: Long?) {
        try {
            runUnsafe(markerSince)
        } catch (x: Throwable) {
            try { onFault(x) } catch (_: Throwable) {}
        }
    }

    private fun runUnsafe(markerSince: Long?) {
        val t0 = now()
        val on = enabled()
        val cursor = readCursor() ?: (t0 - FIRST_RUN_LOOKBACK_MS)
        val all = try { source.recent(MAX_READ) } catch (x: Throwable) { onFault(x); emptyList() }
        val fresh = all.filter { it.timestampMs > cursor }.sortedBy { it.timestampMs }
        var sent = 0
        for (e in fresh) {
            if (!on || sent >= MAX_PER_START) break
            if (!ExitRecord.isAbnormal(e.reason)) continue
            if (e.reason == ExitRecord.R_CRASH && isReportedCrash(e.timestampMs)) continue
            val startup = markerSince != null && e.timestampMs >= markerSince && ExitRecord.isStartupFatal(e.reason)
            val platform = if (e.reason == ExitRecord.R_ANR || e.reason == ExitRecord.R_CRASH_NATIVE) {
                try { ExitRecord.traceOf(e.traceLines()) } catch (_: Throwable) { emptyList() }
            } else emptyList()
            // A Go panic aborts the process with no Java exception, so it surfaces only as this one native exit: the Go frames ride on
            // it (never a second record, so nothing to de-dup against A2's `crash`, which a panic does not trigger).
            val go = if (e.reason == ExitRecord.R_CRASH_NATIVE && goLog != null) {
                try { GoPanic.traceOf(goLog.lines(), e.timestampMs) } catch (_: Throwable) { emptyList() }
            } else emptyList()
            val trace = (go + platform).take(ExitRecord.MAX_TRACE)
            val json = ExitRecord.build(
                reason = ExitRecord.reasonWord(e.reason),
                importance = ExitRecord.importanceWord(e.importance),
                phase = if (startup) ExitRecord.PHASE_STARTUP else null,
                trace = trace, id = newId(), appVersion = appVersion, sdk = sdk, tMs = e.timestampMs,
                ageMs = t0 - e.timestampMs, upMs = upMs(), model = model, release = release,
            )
            if (json != null) { enqueue(json); sent++ }
        }
        if (on && markerSince != null && sent < MAX_PER_START && all.none { it.timestampMs >= markerSince }) {
            ExitRecord.build(
                reason = ExitRecord.REASON_STARTUP_DIED, importance = null, phase = ExitRecord.PHASE_STARTUP, trace = emptyList(),
                id = newId(), appVersion = appVersion, sdk = sdk, tMs = markerSince, ageMs = t0 - markerSince, upMs = upMs(),
                model = model, release = release,
            )?.let(enqueue)
        }
        val newest = fresh.maxOfOrNull { it.timestampMs }
        if (newest != null) writeCursor(newest) else if (readCursor() == null) writeCursor(t0)
    }

    private fun isReportedCrash(exitMs: Long): Boolean {
        val c = lastCrashMs() ?: return false
        return exitMs - c in -CRASH_DEDUP_BEFORE_MS..CRASH_DEDUP_MS
    }

    companion object {
        const val MAX_READ = 5
        const val MAX_PER_START = 5
        const val FIRST_RUN_LOOKBACK_MS = 24L * 3600 * 1000
        const val CRASH_DEDUP_MS = 60_000L
        const val CRASH_DEDUP_BEFORE_MS = 5_000L
    }
}
