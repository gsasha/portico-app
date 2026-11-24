package com.meda.portico.core.connlog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where the queue keeps its text across process death (the app: plain preferences; a report holds nothing secret). */
interface ConnLogStore {
    fun read(): String?
    fun write(text: String)

    /** Like [write], but the text is on disk when this returns (a crash handler: the process is about to die). Default: [write]. */
    fun writeDurable(text: String) = write(text)
}

/** One queued report: its id and its compact JSON text. */
data class QueuedReport(val id: String, val json: String)

/**
 * The reports waiting to be sent (DEV-705): one compact JSON object per line, persisted whole on every change. Bounded at
 * [MAX_REPORTS] reports and [MAX_BYTES] bytes, the oldest dropped first and counted ([dropped]). Tolerant: a line that is
 * not a report is skipped; a store that throws costs the queue's content for that call, but a failed READ never makes the
 * next write overwrite what is stored. The parse is cached while the stored text is unchanged (Setup asks every 2 s).
 * Thread-safe.
 */
class ConnLogQueue(private val store: ConnLogStore) {
    private val lock = Any()

    private var cachedText: String? = null
    private var cachedQueue: List<QueuedReport> = emptyList()
    private var cachedDropped = 0

    /** Queue [json]. [durable]: the store has it on disk before this returns (the crash handler; otherwise a plain, possibly lazy write). */
    fun add(json: String, durable: Boolean = false) {
        synchronized(lock) {
            val id = idOf(json) ?: return
            val q = (loadOrNull() ?: return).toMutableList() // a store we could not read is left alone
            if (q.any { it.id == id }) return
            q.add(QueuedReport(id, json))
            var dropped = 0
            while (q.size > MAX_REPORTS || bytes(q) > MAX_BYTES) {
                if (q.isEmpty()) break
                q.removeAt(0)
                dropped++
            }
            save(q, cachedDropped + dropped, durable)
        }
    }

    /** The oldest reports first, at most [maxReports] and [maxBytes] of text (always at least one if any exist). */
    fun batch(maxReports: Int = BATCH_REPORTS, maxBytes: Int = BATCH_BYTES): List<QueuedReport> = synchronized(lock) {
        val out = ArrayList<QueuedReport>()
        var used = 0
        for (r in loadOrNull().orEmpty()) {
            if (out.size >= maxReports || (out.isNotEmpty() && used + r.json.length > maxBytes)) break
            out.add(r)
            used += r.json.length
        }
        out
    }

    fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            val q = loadOrNull() ?: return
            val gone = ids.toSet()
            save(q.filterNot { it.id in gone }, cachedDropped)
        }
    }

    fun size(): Int = synchronized(lock) { loadOrNull().orEmpty().size }
    fun isEmpty(): Boolean = size() == 0

    /** Reports dropped from the queue because it was full, since it was last cleared. */
    fun dropped(): Int = synchronized(lock) {
        loadOrNull()
        cachedDropped
    }

    fun clear() {
        synchronized(lock) { save(emptyList(), 0) }
    }

    // ---- storage: first line `#dropped=N`, then one report per line ----

    /** The parsed queue, or null when the store could not be read. Refreshes the cache when the stored text changed. */
    private fun loadOrNull(): List<QueuedReport>? = try {
        val text = store.read() ?: ""
        if (text != cachedText) {
            val lines = text.lines()
            cachedDropped = lines.firstOrNull()?.takeIf { it.startsWith(HEADER) }?.removePrefix(HEADER)?.toIntOrNull() ?: 0
            cachedQueue = lines.mapNotNull { line ->
                if (line.isBlank() || line.startsWith(HEADER)) null else idOf(line)?.let { QueuedReport(it, line) }
            }
            cachedText = text
        }
        cachedQueue
    } catch (_: Exception) {
        null
    }

    private fun save(q: List<QueuedReport>, dropped: Int, durable: Boolean = false) {
        val text = HEADER + dropped + (if (q.isEmpty()) "" else "\n" + q.joinToString("\n") { it.json })
        try {
            if (durable) store.writeDurable(text) else store.write(text)
            cachedText = text
            cachedQueue = q
            cachedDropped = dropped
        } catch (_: Exception) {
            cachedText = null // the next read decides what is true
        }
    }

    private fun bytes(q: List<QueuedReport>) = q.sumOf { it.json.length + 1 }

    private fun idOf(json: String): String? = try {
        (Json.parseToJsonElement(json) as? JsonObject)?.get("id")?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() && '\n' !in json }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val MAX_REPORTS = 100
        const val MAX_BYTES = 256 * 1024
        const val BATCH_REPORTS = 20
        const val BATCH_BYTES = 48 * 1024
        private const val HEADER = "#dropped="
    }
}
