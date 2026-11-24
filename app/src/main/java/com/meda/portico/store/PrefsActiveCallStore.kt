package com.meda.portico.store

import android.content.SharedPreferences
import com.meda.portico.core.call.ActiveCall
import com.meda.portico.core.call.ActiveCallStore
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The calls this phone has claimed from Home Assistant and not yet released: a SET (a phone may hold two door calls, design
 * 15.13 failure d). A call is written when its claim is granted and taken out when its hang-up is settled (or the call ended on
 * the server's side), so a process that dies mid-call leaves them behind for the next start to release (`AnswerSweep`).
 * Plain prefs, `commit()`: the process may die right after, and it holds opaque ids only.
 *
 * Storage: one string, one line per call (`door TAB call TAB claimedAt`, the ids URL-encoded so no separator can appear in
 * them). The single record the app wrote before (`door_id`/`call_id`/`claimed_at_ms`) reads as a set of one, and the first
 * write of any kind moves it into the set and removes the old keys.
 */
class PrefsActiveCallStore(private val prefs: SharedPreferences) : ActiveCallStore {

    @Synchronized
    override fun loadAll(): List<ActiveCall> {
        val out = linkedMapOf<String, ActiveCall>()
        // The legacy single record first: it is the oldest.
        legacy()?.let { out[it.callId] = it }
        for (line in (prefs.getString(CALLS, null) ?: "").split('\n')) {
            decode(line)?.let { out[it.callId] = it }
        }
        return out.values.toList()
    }

    @Synchronized
    override fun save(call: ActiveCall) {
        val all = loadAll().filter { it.callId != call.callId } + call
        write(all)
    }

    @Synchronized
    override fun remove(callId: String) {
        val all = loadAll()
        if (all.none { it.callId == callId }) return
        write(all.filter { it.callId != callId })
    }

    private fun legacy(): ActiveCall? {
        val door = prefs.getString(DOOR, null) ?: return null
        val call = prefs.getString(CALL, null) ?: return null
        return ActiveCall(door, call, prefs.getLong(AT, 0L))
    }

    private fun write(all: List<ActiveCall>) {
        val e = prefs.edit().remove(DOOR).remove(CALL).remove(AT)
        if (all.isEmpty()) e.remove(CALLS) else e.putString(CALLS, all.joinToString("\n") { encode(it) })
        e.commit()
    }

    private fun encode(c: ActiveCall): String = "${enc(c.doorId)}\t${enc(c.callId)}\t${c.claimedAtMs}"

    private fun decode(line: String): ActiveCall? {
        val parts = line.split('\t')
        if (parts.size != 3) return null
        val at = parts[2].toLongOrNull() ?: return null
        return try {
            ActiveCall(URLDecoder.decode(parts[0], "UTF-8"), URLDecoder.decode(parts[1], "UTF-8"), at)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val FILE = "portico_active_call"
        private const val DOOR = "door_id"
        private const val CALL = "call_id"
        private const val AT = "claimed_at_ms"
        private const val CALLS = "calls_v2"
    }
}
