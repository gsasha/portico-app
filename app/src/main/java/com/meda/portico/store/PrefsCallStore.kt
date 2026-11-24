package com.meda.portico.store

import android.content.SharedPreferences
import com.meda.portico.core.push.CallKey
import com.meda.portico.core.push.CallRecord
import com.meda.portico.core.push.CallRecordCodec
import com.meda.portico.core.push.CallStore
import com.meda.portico.core.push.Contract

/**
 * B's [CallStore] on SharedPreferences: the ONE store the push path and the socket path share
 * (P2 plan §3.3, design §9.5), so a call heard on both rings once.
 *
 * - **Plain prefs, not the encrypted [Settings] file.** A record holds opaque `door_ref` /
 *   `call_id` values, a state and times: nothing secret (the server logs call ids in full). The
 *   encrypted file needs the Android Keystore on the ring hot path, where a Keystore hiccup
 *   ([Settings.open] returns null) must not cost the dedup record, and every value would pay a
 *   per-key AES round. `allowBackup="false"` keeps the file on the phone.
 * - [put] applies [CallStore.merge], keeps the last [capacity] calls (least recently written
 *   goes first, like `InMemoryCallStore`), and writes with a synchronous `commit()`: the FCM
 *   process can die right after `onMessageReceived` returns. A failed commit is reported to
 *   [onCommitFailed]; SharedPreferences has already applied it in memory, so this process
 *   still deduplicates.
 * - [atomically] takes ONE process-wide lock, shared by every instance: the push service thread
 *   and the socket thread may each hold their own instance, and must still not both decide
 *   "ring" for the same call (B's contract). Re-entrant, so `Decision.receive`'s `put` inside
 *   the block is fine.
 *
 * No `android.*` call beyond the [SharedPreferences] interface, so the JVM tests drive it with
 * an in-memory fake.
 */
class PrefsCallStore(
    private val prefs: SharedPreferences,
    private val capacity: Int = Contract.STORE_CAPACITY,
    private val onCommitFailed: () -> Unit = {},
) : CallStore {

    private val lock: Any get() = PROCESS_LOCK

    private fun load(): MutableList<CallRecord> = CallRecordCodec.decode(prefs.getString(KEY, null)).toMutableList()

    override fun get(key: CallKey): CallRecord? = synchronized(lock) { load().lastOrNull { it.key == key } }

    override fun put(record: CallRecord) {
        synchronized(lock) {
            val list = load()
            val i = list.indexOfFirst { it.key == record.key }
            val existing = if (i >= 0) list.removeAt(i) else null
            list.add(CallStore.merge(existing, record))
            while (list.size > capacity) list.removeAt(0)
            if (!prefs.edit().putString(KEY, CallRecordCodec.encode(list)).commit()) onCommitFailed()
        }
    }

    override fun remove(key: CallKey) {
        synchronized(lock) {
            val list = load()
            if (list.removeAll { it.key == key } && !prefs.edit().putString(KEY, CallRecordCodec.encode(list)).commit()) onCommitFailed()
        }
    }

    override fun latestFor(doorRef: String): CallRecord? = synchronized(lock) { load().lastOrNull { it.doorRef == doorRef } }

    override fun <T> atomically(block: (CallStore) -> T): T = synchronized(lock) { block(this) }

    companion object {
        /** One lock for every instance in the process (see the class doc). */
        private val PROCESS_LOCK = Any()

        /** The prefs file the app opens for this store. */
        const val FILE = "portico_calls"
        const val KEY = "calls_v1"
    }
}
