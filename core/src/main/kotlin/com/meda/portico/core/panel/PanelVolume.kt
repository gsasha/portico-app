package com.meda.portico.core.panel

/** The two streams Panel mode sets (design section 10): the ring plays on [ALARM], the call on [VOICE_CALL]. */
enum class PanelStream { ALARM, VOICE_CALL }

/** The platform's stream levels; every call may fail (a Do Not Disturb policy can refuse), and a failure is a `false`/`null`, never a throw. */
interface StreamLevels {
    fun level(stream: PanelStream): Int?
    fun max(stream: PanelStream): Int?
    fun set(stream: PanelStream, level: Int): Boolean
}

/** The originals the keeper holds, kept across a process kill. */
interface SavedLevels {
    fun load(): Map<PanelStream, Int>
    fun save(levels: Map<PanelStream, Int>)
}

/**
 * Sets a stream to a configured level for a ring or a call and puts the person's own level back afterwards (design section 10: "the
 * only device setting the app touches"). Rules:
 * - the original level is persisted ONCE per stream, before the first raise ([SavedLevels]); a second raise on the same stream while
 *   the first is still held keeps the first original, so two rings (or a ring and its replacement) never save the raised level;
 * - holders are counted: [restore] puts the original back only when the last holder lets go, and is idempotent;
 * - [restoreAll] runs at process start: a saved original means the last run was killed while raised, so it is put back;
 * - [raise] reports whether the level read back equals the target (that is `ring_level_ok` for the alarm stream).
 */
class PanelVolumeKeeper(private val levels: StreamLevels, private val saved: SavedLevels) {
    private val lock = Any()
    private val holders = HashMap<PanelStream, Int>()

    /** The outcome of the last [raise] per stream, for the heartbeat; absent until one has run. */
    private val lastOk = HashMap<PanelStream, Boolean>()

    /** [pct] of the stream's maximum (1..100), at least 1 step. Returns true when the stream reads back at the target. */
    fun raise(stream: PanelStream, pct: Int): Boolean = synchronized(lock) {
        val max = levels.max(stream)
        val before = levels.level(stream)
        if (max == null || before == null || max <= 0) {
            lastOk[stream] = false
            return false
        }
        val held = holders[stream] ?: 0
        if (held == 0) {
            // the original is saved once; one already on disk is a previous run's, which restoreAll should have put back
            val on = saved.load()
            if (stream !in on) saved.save(on + (stream to before))
        }
        holders[stream] = held + 1
        val target = target(max, pct)
        val ok = levels.set(stream, target) && levels.level(stream) == target
        lastOk[stream] = ok
        ok
    }

    /** One holder lets go; the last one puts the original back. Idempotent (a release with no holder does nothing). */
    fun restore(stream: PanelStream) {
        synchronized(lock) {
            val held = holders[stream] ?: return
            if (held > 1) {
                holders[stream] = held - 1
                return
            }
            holders.remove(stream)
            putBack(stream)
        }
    }

    /** At process start: every saved original is put back. */
    fun restoreAll() {
        synchronized(lock) {
            holders.clear()
            for (s in saved.load().keys.toList()) putBack(s)
        }
    }

    /** Whether the last raise of [stream] worked; true when there has been none (nothing says otherwise yet). */
    fun levelOk(stream: PanelStream): Boolean = synchronized(lock) { lastOk[stream] ?: true }

    private fun putBack(stream: PanelStream) {
        val on = saved.load()
        val original = on[stream] ?: return
        levels.set(stream, original)
        saved.save(on - stream)
    }

    companion object {
        const val DEFAULT_RING_PCT = 80
        const val DEFAULT_CALL_PCT = 70

        /** [pct] of [max], rounded, kept in 1..[max]. */
        fun target(max: Int, pct: Int): Int = Math.round(max * pct.coerceIn(1, 100) / 100f).coerceIn(1, max)
    }
}
