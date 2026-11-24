package com.meda.portico.core.push

/** What the phone knows about one call. RINGING is the only non-terminal state. */
enum class CallState(val seq: Int) {
    RINGING(Contract.SEQ_RING),

    /** Answered elsewhere (or by this phone: the answer path records it before it acts). */
    ANSWERED(Contract.SEQ_ANSWERED),

    /** The call is over. */
    ENDED(Contract.SEQ_ENDED),

    /** A ring arrived after `exp + skew`: "Missed call" was posted, nothing rang. */
    MISSED(Contract.SEQ_RING),
    ;

    val terminal: Boolean get() = this != RINGING
}

/**
 * One call's record, keyed by `doorRef + callId`. [seq] is the highest `seq` seen for the call
 * (a socket event carries no `seq`; it uses its state's [CallState.seq]).
 */
data class CallRecord(
    val doorRef: String,
    val callId: String,
    val state: CallState,
    val seq: Int,
    val tMs: Long,
    val source: Source,
    val updatedAtMs: Long,
) {
    val key: CallKey get() = CallKey(doorRef, callId)
}

data class CallKey(val doorRef: String, val callId: String)

/**
 * The one call store both delivery paths share (P2 plan §3.3; design §9.5 "one shared store"), so
 * a phone that hears a call over the WebSocket **and** by push rings once.
 *
 * **Contract for implementations** (`InMemoryCallStore` here; `PrefsCallStore` in the app):
 * - [put] must be durable when it returns. The app's store writes with `commit()`, because the
 *   FCM process can be killed right after `onMessageReceived` returns, and an unsaved dedup
 *   record double-rings. The record is written **before** any notification is posted.
 * - [put] applies [merge]: a record never moves to a lower `seq`, so a late write from either path
 *   cannot resurrect a finished call.
 * - At most [Contract.STORE_CAPACITY] calls are kept; the least recently written goes first.
 * - [atomically] runs read-decide-write as one step, so the FCM service thread and the socket
 *   thread cannot both decide "ring" for the same call. A JVM lock is enough: both paths run in
 *   the app's one process.
 *
 * **Writers.** Never call [put] directly from a delivery path: go through [Decision.receive],
 * which decides and records in one [atomically] block. The socket path (PR-D) builds its messages
 * with [SocketEvents] and calls the same [Decision.receive].
 */
interface CallStore {
    fun get(key: CallKey): CallRecord?

    /** Write [record], merged with any existing record by [merge]. */
    fun put(record: CallRecord)

    /**
     * Drop the record for [key]. Not a delivery path: [put] never lowers a `seq`, so the answer path
     * uses this to take back the ANSWERED it recorded for a claim that never went out.
     */
    fun remove(key: CallKey)

    /** The most recently written record for [doorRef], or null. Used by the socket's G1 fallback. */
    fun latestFor(doorRef: String): CallRecord?

    /** Run [block] with exclusive access to the store. */
    fun <T> atomically(block: (CallStore) -> T): T

    companion object {
        /**
         * The merge rule both paths obey: the higher `seq` wins; on equal `seq` the existing record
         * stays, except that RINGING may become MISSED (both seq 1) never the reverse.
         */
        fun merge(existing: CallRecord?, incoming: CallRecord): CallRecord = when {
            existing == null -> incoming
            incoming.seq > existing.seq -> incoming
            incoming.seq == existing.seq &&
                existing.state == CallState.RINGING && incoming.state == CallState.MISSED -> incoming
            else -> existing
        }
    }
}

/** A bounded in-memory [CallStore], for tests and as the reference for the app's store. */
class InMemoryCallStore(private val capacity: Int = Contract.STORE_CAPACITY) : CallStore {
    // Insertion-ordered; a write removes and re-inserts, so the first entry is the least recently written.
    private val records = LinkedHashMap<CallKey, CallRecord>()
    private val lock = Any()

    override fun get(key: CallKey): CallRecord? = synchronized(lock) { records[key] }

    override fun put(record: CallRecord) {
        synchronized(lock) {
            val merged = CallStore.merge(records.remove(record.key), record)
            records[record.key] = merged
            while (records.size > capacity) {
                records.remove(records.keys.first())
            }
        }
    }

    override fun remove(key: CallKey) {
        synchronized(lock) { records.remove(key) }
    }

    override fun latestFor(doorRef: String): CallRecord? = synchronized(lock) {
        records.values.lastOrNull { it.doorRef == doorRef }
    }

    override fun <T> atomically(block: (CallStore) -> T): T = synchronized(lock) { block(this) }

    /** Number of calls held (tests). */
    val size: Int get() = synchronized(lock) { records.size }
}
