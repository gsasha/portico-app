package com.meda.portico.core.connlog

import kotlinx.serialization.json.JsonObject

/** What `portico/connlog/report` answered. */
sealed interface ConnLogReply {
    data class Ok(
        val stored: Int,
        val duplicates: Int,
        val rejected: Int,
        /** `journal.lines` / `journal.dropped` of a reply to a message that carried Journal items (0 when HA did not say). */
        val journalLines: Int = 0,
        val journalDropped: Int = 0,
    ) : ConnLogReply

    /** HA refused: [code] is its error code (`unknown_command`, `rate_limited`, `too_large`, `unauthorized`...). */
    data class Error(val code: String) : ConnLogReply

    /** The socket closed before the answer (or never answered). */
    data object Lost : ConnLogReply
}

/**
 * Sends the queued reports (DEV-705, design section 4). [kick] is called when the link is CONNECTED and when a report is
 * queued; it sends the oldest batch if nothing is in flight, the sender is enabled and its back-off allows. Rules:
 * - one batch in flight, at least [MIN_GAP_MS] between batches, at most ONE pending timer;
 * - an error backs off 15 minutes, doubling to 6 hours; `unknown_command` (a plugin without the command) switches it off
 *   for this process;
 * - `too_large` and `invalid_format` bisect: the batch is halved and tried again, and a single report that is itself refused
 *   is DROPPED (a poison report must not block the queue behind it); a success restores the full batch size;
 * - a call nobody answers is [ConnLogReply.Lost] after [ANSWER_TIMEOUT_MS]; a lost socket is not a failure, the next
 *   CONNECTED kicks again.
 *
 * [send] puts one `portico/connlog/report` (the body from [ConnLogCommands.report]) on the socket and calls back with the
 * reply; false means nothing was sent. [schedule] runs a task later. TOTAL and thread-safe: it never touches the connect.
 */
class ConnLogSender(
    private val queue: ConnLogQueue,
    private val send: (body: JsonObject, onReply: (ConnLogReply) -> Unit) -> Boolean,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val enabled: () -> Boolean = { true },
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var inFlight = false
    private var flight = 0
    private var kickPending = false
    private var nextAllowedAt = 0L
    private var failures = 0
    private var unsupported = false
    private var batchReports = ConnLogQueue.BATCH_REPORTS

    @Volatile
    var lastSentAtMs: Long = 0L
        private set

    /** Reports accepted by HA since this process started (for Setup). */
    @Volatile
    var sentCount: Int = 0
        private set

    val isUnsupported: Boolean get() = synchronized(lock) { unsupported }

    fun kick() {
        try {
            val body: JsonObject
            val ids: List<String>
            val mine: Int
            synchronized(lock) {
                if (!enabled() || unsupported || inFlight || queue.isEmpty()) return
                val now = clockMs()
                if (now < nextAllowedAt) {
                    later(nextAllowedAt - now)
                    return
                }
                val batch = queue.batch(batchReports)
                if (batch.isEmpty()) return
                ids = batch.map { it.id }
                body = ConnLogCommands.report(batch.map { it.json })
                inFlight = true
                mine = ++flight
            }
            schedule(ANSWER_TIMEOUT_MS) { answered(mine, ids, ConnLogReply.Lost) } // a silent server must not stick the sender
            val accepted = send(body) { reply -> answered(mine, ids, reply) }
            if (!accepted) synchronized(lock) { if (flight == mine) inFlight = false }
        } catch (_: Exception) {
            synchronized(lock) { inFlight = false }
        }
    }

    /** Schedule one kick, unless one is already pending. Call under [lock]. */
    private fun later(delayMs: Long) {
        if (kickPending) return
        kickPending = true
        schedule(delayMs.coerceAtLeast(0)) {
            synchronized(lock) { kickPending = false }
            kick()
        }
    }

    /** The reply (or the timeout) of flight [mine]; a second report for the same flight is ignored. */
    private fun answered(mine: Int, ids: List<String>, reply: ConnLogReply) {
        synchronized(lock) {
            if (flight != mine || !inFlight) return
            inFlight = false
            val now = clockMs()
            when (reply) {
                is ConnLogReply.Ok -> {
                    queue.remove(ids) // stored, duplicate or rejected: none of them will ever be accepted differently
                    failures = 0
                    batchReports = ConnLogQueue.BATCH_REPORTS
                    sentCount += reply.stored
                    lastSentAtMs = now
                    nextAllowedAt = now + MIN_GAP_MS
                    if (!queue.isEmpty()) later(MIN_GAP_MS)
                }
                is ConnLogReply.Error -> when (reply.code) {
                    "unknown_command" -> unsupported = true
                    "too_large", "invalid_format" -> {
                        if (ids.size > 1) {
                            batchReports = (ids.size / 2).coerceAtLeast(1)
                        } else {
                            queue.remove(ids) // this one report is the problem: drop it, do not block the rest
                        }
                        nextAllowedAt = now + MIN_GAP_MS
                        later(MIN_GAP_MS)
                    }
                    else -> {
                        failures++
                        nextAllowedAt = now + backoff(failures)
                    }
                }
                ConnLogReply.Lost -> nextAllowedAt = now + MIN_GAP_MS // the next CONNECTED kicks again
            }
        }
    }

    companion object {
        /** The command the body goes with (the link adds the type and the id). */
        const val COMMAND = ConnLogCommands.REPORT
        const val MIN_GAP_MS = 10_000L
        const val ANSWER_TIMEOUT_MS = 60_000L
        private const val BACKOFF_FIRST_MS = 15L * 60 * 1000
        private const val BACKOFF_MAX_MS = 6L * 3600 * 1000

        fun backoff(failures: Int): Long {
            var d = BACKOFF_FIRST_MS
            repeat((failures - 1).coerceIn(0, 10)) { d = (d * 2).coerceAtMost(BACKOFF_MAX_MS) }
            return d
        }
    }
}
