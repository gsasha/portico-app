package com.meda.portico.core.ha

/**
 * Where the time goes in one connect (DEV-694, P0). The away connect is a chain: the direct
 * attempt, then the Go library load, the tsnet start, the forwarder, and the socket. Nobody has
 * measured the links, so this records them. It changes no behaviour: marks are written by
 * [FallbackConnector] and [com.meda.portico.core.tailnet.TailnetController] and read by Setup.
 *
 * A connect starts with [begin] (the direct attempt starts); each [Stage] is then recorded the
 * first time it happens, as an offset from that start. A new [begin] replaces the previous
 * timeline, so a second connect that overlaps the first overwrites it (P0 reads the latest only).
 * A mark made before any [begin] is ignored. Offsets, never wall-clock times and never an
 * address, a key or a token: a line built from them is safe to show.
 *
 * Thread-safe: the connector's worker, the tailnet worker and the UI thread all touch it.
 */
class ConnectTimeline(private val clockMs: () -> Long = System::currentTimeMillis) {

    /** The links of the chain, in the order they usually happen. [label] is the words Setup shows. */
    enum class Stage(val label: String) {
        DIRECT_OPENED("direct open"),
        DIRECT_FAILED("direct failed"),
        TAILNET_STARTED("tailnet started"),
        LIBRARY_READY("library loaded"),
        NODE_UP("node up"),
        FORWARDER_READY("forwarder ready"),
        TAILNET_OPENED("tailnet open"),
        TAILNET_FAILED("tailnet failed"),

        /**
         * DEV-798: Home Assistant proved itself before a token went to a cleartext address ([com.meda.portico.core.auth.HomeVerifier]).
         * The connection log gets the fixed word of why: `refused` or `no_answer` for a failure, `tls` or `no_token` for a skip.
         */
        PROOF_OK("proof ok"),
        PROOF_FAILED("proof failed"),
        PROOF_SKIPPED("proof skipped");

        /** The word the connection log uses: `direct_opened`, `library_ready`... */
        val wire: String get() = name.lowercase()
    }

    /** One stage and when it happened, in ms after the connect began. */
    data class Mark(val stage: Stage, val offsetMs: Long)

    /**
     * The marks of one connect, earliest first. [startedAtMs] is the wall clock of [begin]. [note]: tsnet's own phase
     * times for this connect's join ("starting+30ms running+285ms"), when there were any.
     */
    data class Snapshot(val startedAtMs: Long, val marks: List<Mark>, val note: String? = null)

    /**
     * DEV-705: the connection log listens to every mark as it happens (the timeline keeps only the latest connect; a
     * sequence can contain several). Set once by the app; calls are guarded, so a throwing observer costs the log, not the connect.
     */
    interface Observer {
        fun begin() {}
        fun mark(stage: Stage, cls: String?) {}
        fun note(text: String) {}

        companion object {
            val NONE = object : Observer {}
        }
    }

    @Volatile
    var observer: Observer = Observer.NONE

    private val lock = Any()
    private var startedAtMs = -1L
    private val marks = LinkedHashMap<Stage, Long>()
    private var note: String? = null

    /** A connect begins (the direct attempt starts): forget the previous one. */
    fun begin() {
        synchronized(lock) {
            startedAtMs = clockMs()
            marks.clear()
            note = null
        }
        runCatching { observer.begin() } // outside the lock: the observer takes its own
    }

    /** True when [stage] was recorded in the current connect. */
    fun has(stage: Stage): Boolean = synchronized(lock) { startedAtMs >= 0 && marks.containsKey(stage) }

    /**
     * Attach tsnet's phase times to the current connect. Kept only if it is words, digits, `+` and spaces (what the Go side
     * writes): anything else is dropped, so nothing but timings can reach Setup or the Journal.
     */
    fun note(text: String?) {
        val kept = synchronized(lock) {
            if (startedAtMs < 0) return
            val t = text?.trim().orEmpty()
            note = if (t.isNotEmpty() && t.length <= MAX_NOTE && NOTE.matches(t)) t else null
            note
        }
        if (kept != null) runCatching { observer.note(kept) }
    }

    /** Record [stage] now, unless it was already recorded in this connect or none has begun. */
    fun mark(stage: Stage, cls: String? = null) {
        val recorded = synchronized(lock) {
            if (startedAtMs < 0 || marks.containsKey(stage)) return@synchronized false
            marks[stage] = (clockMs() - startedAtMs).coerceAtLeast(0)
            true
        }
        if (recorded) runCatching { observer.mark(stage, cls) }
    }

    /** The latest connect's marks, or null when none has begun. Marks are sorted by time, ties in [Stage] order. */
    fun snapshot(): Snapshot? = synchronized(lock) {
        if (startedAtMs < 0) return@synchronized null
        Snapshot(startedAtMs, marks.map { Mark(it.key, it.value) }.sortedWith(compareBy({ it.offsetMs }, { it.stage.ordinal })), note)
    }

    companion object {
        private const val MAX_NOTE = 120
        private val NOTE = Regex("[a-z0-9+ ]+")

        /** A timeline nobody reads, for callers that do not measure (it records, harmlessly). */
        fun none() = ConnectTimeline()

        /** "direct failed +120 ms, library loaded +1.9 s, node up +4.3 s" (null when nothing was marked). */
        fun summary(s: Snapshot?): String? {
            if (s == null || s.marks.isEmpty()) return null
            return s.marks.joinToString(", ") { "${it.stage.label} +${offset(it.offsetMs)}" } +
                (s.note?.let { " [tsnet $it]" } ?: "")
        }

        /** Locale-free: under a second in ms, else seconds with one decimal ("1.9 s", "12.0 s"). */
        fun offset(ms: Long): String =
            if (ms < 1000) "$ms ms" else "${ms / 1000}.${(ms % 1000) / 100} s"
    }
}
