package com.meda.portico.core.push

import java.util.TimeZone

/**
 * Where the push and ring journal keeps its text between processes. The app's is a SharedPreferences string; the tests'
 * is [InMemoryPushJournalStore]. [read] may return null (nothing yet) or anything at all (a half-written or foreign value):
 * [PushJournal] tolerates it. Neither call may be relied on not to throw: the journal catches.
 */
interface PushJournalStore {
    fun read(): String?

    /** Replace the whole text; true if it was stored. The app's does one synchronous, atomic prefs commit. */
    fun write(text: String): Boolean
}

class InMemoryPushJournalStore(initial: String? = null) : PushJournalStore {
    @Volatile
    var text: String? = initial

    @Volatile
    var writes = 0

    override fun read(): String? = text
    override fun write(text: String): Boolean {
        this.text = text
        writes++
        return true
    }
}

/**
 * What the phone allowed at the moment of a decision. Every field is nullable ("could not be read"). The strings are the
 * producer's own words ([PushJournalFormat] lets only short lowercase words through), never a name or an id.
 */
data class PushEnv(
    /** A Portico screen (Home, Settings) is shown. */
    val foreground: Boolean? = null,
    /** `NotificationManager.canUseFullScreenIntent()` (Android 14+; true below, where the manifest grant is enough). */
    val fullScreen: Boolean? = null,
    /** The ring channel: `ok`, `lowered`, `blocked` or `missing`. */
    val ringChannel: String? = null,
    /** The ring channel's `getImportance()` (4 = high). */
    val ringImportance: Int? = null,
    /** `areNotificationsEnabled()`. */
    val notifications: Boolean? = null,
    /** The Do Not Disturb filter: `off`, `priority`, `alarms` or `none`. */
    val dnd: String? = null,
)

/**
 * The push and ring journal (0.5.12-alpha): one line per push received and per ring, answer or dismiss outcome, so what the
 * app DECIDED can be read on the phone instead of out of adb's logcat. It is write-only observation: nothing here is read
 * by the decision path, and no method throws.
 *
 * - **Durable.** The push receiver may run in a fresh process, so every [add] reads the text from the [store], appends and
 *   writes it back whole (one atomic prefs commit in the app). Order across process restarts is therefore the order of
 *   the writes.
 * - **Bounded.** At most [MAX_LINES] lines and [MAX_BYTES] bytes (UTF-8); the oldest go first. A line is cut at
 *   [MAX_LINE] characters.
 * - **Redacted twice.** [PushJournalFormat] only ever receives ids to shorten (first six characters and the length) and
 *   enum names; and [add] scrubs whatever still looks like an id, a token or a URL ([PushJournalFormat.scrub]).
 * - **Tolerant.** A stored text that does not start with [HEADER] (empty, truncated, foreign, garbled) is treated as empty;
 *   control characters in a stored line are dropped; a store that throws costs the line, not the push.
 * - **Thread-safe** across instances: one process-wide lock around read-modify-write.
 *
 * [clockMs], [zone] and [env] are read per line; [env] is the Android probe (fullscreen / channel / DND / foreground).
 */
class PushJournal(
    private val store: PushJournalStore,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val zone: () -> TimeZone = TimeZone::getDefault,
    private val env: () -> PushEnv = { PushEnv() },
) {

    // ---- the ring buffer -----------------------------------------------------------------------------------------

    /** Append [line] (scrubbed and cut), trimming to the caps, and write it through. False if nothing was stored. */
    fun add(line: String): Boolean = safe(false) {
        val clean = PushJournalFormat.scrub(line)
        if (clean.isEmpty()) return@safe false
        val stored = try {
            synchronized(LOCK) {
                val lines = load().toMutableList()
                lines.add(clean)
                store.write(encode(trim(lines)))
            }
        } finally {
            offerToObserver(clean) // after the lock; the reporter's own work is in-memory and never waits on this journal
        }
        stored
    }

    /**
     * DEV-713 / DEV-705: the ONE place a line leaves the Journal for someone else. The connection log's journal reporter sets it (it keeps only
     * `live` and `audio` lines). Called with each line as it was stored, after the store write, whether or not the write worked. Never throws.
     */
    @Volatile
    var lineObserver: ((String) -> Unit)? = null

    private fun offerToObserver(clean: String) {
        try {
            lineObserver?.invoke(clean)
        } catch (e: Throwable) {
            // an observer must never cost a line, a ring or a push
        }
    }

    /** All lines, oldest first. Empty on any failure. */
    fun lines(): List<String> = safe(emptyList()) { synchronized(LOCK) { load() } }

    fun text(): String = lines().joinToString("\n")

    /** The last [n] lines, oldest first. */
    fun tail(n: Int): List<String> = lines().takeLast(n.coerceAtLeast(0))

    fun isEmpty(): Boolean = lines().isEmpty()

    fun clear(): Boolean = safe(false) { synchronized(LOCK) { store.write(HEADER) } }

    private fun load(): List<String> {
        val raw = try {
            store.read()
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        if (!raw.startsWith(HEADER)) return emptyList() // not ours, or cut short before the header: start again
        return trim(raw.removePrefix(HEADER).split('\n').map(PushJournalFormat::scrub).filter { it.isNotEmpty() })
    }

    // ---- observations: what the receive path reports ---------------------------------------------------------------

    /**
     * A push (or a socket call event turned into the same message) was decided: one line, written before the outcome is
     * acted on. [nowMs] is the clock the decision used. [knownDoors] is the size of `knownDoorRefs` (null: unreadable).
     */
    fun decided(msg: PushMessage, outcome: Outcome, nowMs: Long, knownDoors: Int?) {
        safe(Unit) { add(PushJournalFormat.decisionLine(nowMs, zone(), msg, outcome, knownDoors, safeEnv())) }
    }

    /** `decide` threw (the exception class only). */
    fun decideFailed(msg: PushMessage, nowMs: Long, e: Throwable) {
        safe(Unit) { add(PushJournalFormat.failedLine(nowMs, zone(), msg, e, safeEnv())) }
    }

    /** A message dropped before any decision (the pause): its kind word only. */
    fun dropped(kind: String, why: String) {
        safe(Unit) { add(PushJournalFormat.droppedLine(clockMs(), zone(), kind, why)) }
    }

    /** A `portico/calls` event arrived on the socket (before any decision). */
    fun socketEvent(event: com.meda.portico.core.ha.CallEvent) {
        safe(Unit) { add(PushJournalFormat.socketEventLine(clockMs(), zone(), event)) }
    }

    /** A decided outcome that the ring switch or the pause kept from the handler. */
    fun gated(outcome: Outcome, why: String) {
        safe(Unit) { add(PushJournalFormat.gatedLine(clockMs(), zone(), outcome, why)) }
    }

    /** What the handler did for [outcome]: the plan it applied and the permission state it applied it under. */
    fun handled(outcome: Outcome, plan: com.meda.portico.core.ring.NotificationPlan, perms: com.meda.portico.core.ring.PermissionState) {
        safe(Unit) { add(PushJournalFormat.handledLine(clockMs(), zone(), outcome, plan, perms)) }
    }

    /** A handler or dispatcher step threw: the step's word and the exception class. */
    fun failed(what: String, e: Throwable) {
        safe(Unit) { add(PushJournalFormat.stepFailedLine(clockMs(), zone(), what, e)) }
    }

    /** A ring was stopped (decline, backstop, the ring screen's button, a pause). */
    fun stopped(why: String, doorRef: String?, callId: String?, stopped: Boolean) {
        safe(Unit) { add(PushJournalFormat.stopLine(clockMs(), zone(), why, doorRef, callId, stopped)) }
    }

    /** A Talk to the door ended: which code path ended it, and how soon after the press. */
    fun talkEnd(e: com.meda.portico.core.call.TalkEnd) {
        safe(Unit) { add(PushJournalFormat.talkEndLine(clockMs(), zone(), e)) }
    }

    /** DEV-768 P3: a camera viewport's pick or resize (the body is `CameraViewport.pickLine` / `resizeLine`). */
    fun cameraView(body: String) {
        safe(Unit) { add(PushJournalFormat.time(clockMs(), zone()) + " " + body) }
    }

    /** Every ring was stopped (the pause): how many. */
    fun stoppedAll(why: String, n: Int) {
        safe(Unit) { add(PushJournalFormat.stopAllLine(clockMs(), zone(), why, n)) }
    }

    /** A hold, switch or resume step (M7; only ever called while `HoldConfig.HOLD_ENABLED`): ids are shortened by the formatter. */
    fun hold(step: HoldStep, doorRef: String?, callId: String?, result: Enum<*>? = null, ms: Long? = null, n: Int? = null) {
        safe(Unit) { add(PushJournalFormat.holdLine(clockMs(), zone(), step, doorRef, callId, result, ms, n)) }
    }

    /** A step of a house ring (M7 3b-B). Ids are shortened by the formatter. */
    fun house(step: HouseStep, callId: String?, result: Enum<*>? = null, n: Int? = null) {
        safe(Unit) { add(PushJournalFormat.houseLine(clockMs(), zone(), step, callId, result, n)) }
    }

    /** The two lines of one call's frame audio at its close (DEV-811 P1): the flow numbers and the device numbers. [route] is where the sound came out. */
    fun frameAudio(r: com.meda.portico.core.call.FrameReport, route: String) {
        safe(Unit) { add(PushJournalFormat.framesLine(clockMs(), zone(), r.callId, r.profile, r.flow)) }
        safe(Unit) { add(PushJournalFormat.deviceLine(clockMs(), zone(), r, route)) }
    }

    /** One call's audio facts, at the start of its audio or when its media closed (DEV-698); formatted and redacted by [PushJournalFormat.audioLine]. */
    fun audio(phase: AudioPhase, path: String, callId: String?, stats: com.meda.portico.core.call.AudioStats?, muted: Boolean, why: com.meda.portico.core.call.EndReason?) {
        safe(Unit) { add(PushJournalFormat.audioLine(clockMs(), zone(), phase, path, callId, stats, muted, why)) }
    }

    /** DEV-694: a connect opened [via] `direct` or `tailnet`, with where its time went; formatted by [PushJournalFormat.connectLine]. */
    fun connected(via: String, summary: String?) {
        safe(Unit) { add(PushJournalFormat.connectLine(clockMs(), zone(), via, summary)) }
    }

    /**
     * DEV-764: one compact diagnostic event (see [PushJournalFormat.diagLine]); `word` is `ui`, `net` or `ws`. Observation only, never throws.
     * Use [PushJournalFormat.DiagId] for any id.
     */
    fun diag(word: String, event: String, fields: List<Pair<String, Any?>>) {
        safe(Unit) { add(PushJournalFormat.diagLine(clockMs(), zone(), word, event, fields)) }
    }

    fun diag(word: String, event: String, vararg fields: Pair<String, Any?>) = diag(word, event, fields.toList())

    /** DEV-713: the journal reporter failed (the exception class only). Not a `live` or `audio` line, so it is never reported itself. */
    fun journalReportFault(e: Throwable) {
        safe(Unit) { add(PushJournalFormat.journalFaultLine(clockMs(), zone(), e)) }
    }

    /** DEV-713: the live-video lines (`live start|fail|stop`), as the session's [com.meda.portico.core.live.LiveLog]. Observation only. */
    val liveLog: com.meda.portico.core.live.LiveLog = object : com.meda.portico.core.live.LiveLog {
        override fun started(key: com.meda.portico.core.live.LiveKey, stream: String?, ttffMs: Long) {
            safe(Unit) { add(PushJournalFormat.liveLine(clockMs(), zone(), LiveStep.START, key.cameraId, key.size.wire, stream = if (key.rtsp) null else stream, ttffMs = ttffMs, rtspStream = key.stream)) }
        }

        override fun failed(key: com.meda.portico.core.live.LiveKey, why: com.meda.portico.core.live.LiveWhy, retry: Int, willRetry: Boolean) {
            safe(Unit) { add(PushJournalFormat.liveLine(clockMs(), zone(), LiveStep.FAIL, key.cameraId, key.size.wire, why = why.word, retry = retry, willRetry = willRetry, rtspStream = key.stream)) }
        }

        override fun stopped(key: com.meda.portico.core.live.LiveKey, why: com.meda.portico.core.live.StopWhy, playedMs: Long) {
            safe(Unit) { add(PushJournalFormat.liveLine(clockMs(), zone(), LiveStep.STOP, key.cameraId, key.size.wire, why = why.word, playedSecs = playedMs / 1000, rtspStream = key.stream)) }
        }

        override fun thermal(status: Int, hot: Boolean) {
            safe(Unit) { add(PushJournalFormat.liveThermalLine(clockMs(), zone(), hot, com.meda.portico.core.live.ThermalGuard.word(status))) }
        }

        override fun diag(event: String, key: com.meda.portico.core.live.LiveKey?, fields: List<Pair<String, Any?>>) {
            safe(Unit) {
                val head = if (key == null) emptyList() else listOf<Pair<String, Any?>>("cam" to PushJournalFormat.DiagId(key.cameraId), "size" to key.size.wire, "rstream" to key.stream)
                add(PushJournalFormat.diagLine(clockMs(), zone(), "ui", event, head + fields))
            }
        }

        override fun fault(at: String, error: String) {
            safe(Unit) { add(PushJournalFormat.liveFaultLine(clockMs(), zone(), at, error)) }
        }
    }

    private fun safeEnv(): PushEnv = try {
        env()
    } catch (e: Throwable) {
        PushEnv()
    }

    /** The journal never throws into the receive path. (Throwable: an Error in a probe must not cost a ring either.) */
    private inline fun <T> safe(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        fallback
    }

    companion object {
        /**
         * DEV-764: sized to keep at least a normal day (24 h) on the phone: about 90 bytes and 300 to 600 lines a day per phone (ring decisions,
         * live-video sessions, `ui`/`net` diagnostics), so 1500 lines is 2 to 5 days, and a busy day (live video all afternoon) still holds
         * the morning. The cap is the bytes: 160 KB, one synchronous prefs commit of at most that much per line. Was 200 lines / 32 KB.
         */
        const val MAX_LINES = 1500
        const val MAX_BYTES = 160 * 1024
        const val MAX_LINE = 300

        /** First line of the stored text; anything else means the text is not ours. */
        const val HEADER = "pj1\n"

        private val LOCK = Any()

        private fun encode(lines: List<String>): String = HEADER + lines.joinToString("\n")

        /** Drop the oldest lines until both caps hold. */
        internal fun trim(lines: List<String>): List<String> {
            var from = (lines.size - MAX_LINES).coerceAtLeast(0)
            var bytes = HEADER.length + lines.drop(from).sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }
            while (from < lines.size && bytes > MAX_BYTES) {
                bytes -= lines[from].toByteArray(Charsets.UTF_8).size + 1
                from++
            }
            return lines.drop(from)
        }
    }
}
