package com.meda.portico.ring

import android.content.SharedPreferences
import com.meda.portico.core.push.CallKey
import com.meda.portico.core.ring.DoorSlotStore
import com.meda.portico.core.ring.LastStatus
import com.meda.portico.core.ring.NoteKind

/**
 * What the ring UI remembers per door, on one plain prefs file ([FILE]), every write a synchronous
 * `commit()` (the FCM process can die right after `onMessageReceived` returns):
 *
 * - the call whose ring is up (so a dismiss, a decline or the alarm backstop stops only THAT call);
 * - the status notification last posted (so "ended after answered" can re-post it silently);
 * - [DoorSlots][com.meda.portico.core.ring.DoorSlots]' table (request codes must agree
 *   across process deaths);
 * - PR-G: whether the last ring's CallStyle was refused (an exception class name, or absent), for
 *   Setup ([callStyleRefusedBy]).
 *
 * Plain, not encrypted, for the same reasons as `PrefsCallStore`: opaque ids only, read on the ring
 * hot path. Only the [SharedPreferences] interface is used, so the JVM tests use a fake.
 */
class RingState(private val prefs: SharedPreferences) : DoorSlotStore {

    fun activeRing(doorRef: String): String? = prefs.getString(ringKey(doorRef), null)

    fun setActiveRing(doorRef: String, callId: String) {
        prefs.edit().putString(ringKey(doorRef), callId).commit()
    }

    /** Clears the door's active ring if it is [callId]; true if it was. */
    fun clearActiveRing(doorRef: String, callId: String): Boolean {
        if (activeRing(doorRef) != callId) return false
        prefs.edit().remove(ringKey(doorRef)).commit()
        return true
    }

    /** Pause: every door's ring that is up, door -> call id. */
    fun activeRings(): Map<String, String> = prefs.all.entries
        .filter { it.key.startsWith(RING_PREFIX) && it.value is String }
        .associate { it.key.removePrefix(RING_PREFIX) to it.value as String }

    fun lastStatus(doorRef: String): LastStatus? {
        val v = prefs.getString(statusKey(doorRef), null) ?: return null
        val parts = v.split('\t')
        if (parts.size != 2) return null
        val kind = NoteKind.values().firstOrNull { it.name == parts[1] } ?: return null
        return LastStatus(CallKey(doorRef, parts[0]), kind)
    }

    fun setLastStatus(s: LastStatus) {
        prefs.edit().putString(statusKey(s.key.doorRef), "${s.key.callId}\t${s.kind.name}").commit()
    }

    override fun load(): String? = prefs.getString(SLOTS_KEY, null)

    override fun save(encoded: String) {
        prefs.edit().putString(SLOTS_KEY, encoded).commit()
    }

    /** PR-G: the class name of the last CallStyle refusal, or null if the last CallStyle was accepted. */
    fun callStyleRefusedBy(): String? = prefs.getString(CALL_STYLE_KEY, null)

    /** PR-G: null clears it (CallStyle accepted); else the refusal's class name. Writes only on a change. */
    fun setCallStyleRefusedBy(refusedBy: String?) {
        if (callStyleRefusedBy() == refusedBy) return
        val e = prefs.edit()
        if (refusedBy == null) e.remove(CALL_STYLE_KEY) else e.putString(CALL_STYLE_KEY, refusedBy)
        e.commit()
    }

    private fun ringKey(doorRef: String) = "$RING_PREFIX$doorRef"
    private fun statusKey(doorRef: String) = "status.$doorRef"

    companion object {
        const val FILE = "portico_ring"
        private const val RING_PREFIX = "ring."
        private const val SLOTS_KEY = "slots_v1"
        private const val CALL_STYLE_KEY = "callstyle_refused_v1"
    }
}
