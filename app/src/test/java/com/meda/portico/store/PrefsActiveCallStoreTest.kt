package com.meda.portico.store

import com.meda.portico.core.call.ActiveCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The crash record is a SET of claimed calls (design 15.13 failure d), and the old single record reads as a set of one. */
class PrefsActiveCallStoreTest {

    private val prefs = FakeSharedPreferences()
    private val store = PrefsActiveCallStore(prefs)

    @Test
    fun `empty store has nothing`() {
        assertTrue(store.loadAll().isEmpty())
        assertEquals(null, store.load())
    }

    @Test
    fun `two calls are kept, oldest first, and one is taken out at a time`() {
        store.save(ActiveCall("gate-dev", "gso-a", 10))
        store.save(ActiveCall("ent-dev", "gso-b", 20))
        assertEquals(listOf("gso-a", "gso-b"), store.loadAll().map { it.callId })
        assertEquals(ActiveCall("ent-dev", "gso-b", 20), store.loadAll()[1])
        store.remove("gso-a")
        assertEquals(listOf("gso-b"), store.loadAll().map { it.callId })
        store.remove("gso-b")
        assertTrue(store.loadAll().isEmpty())
        assertTrue(prefs.all.isEmpty(), "nothing is left behind")
    }

    @Test
    fun `saving the same call again replaces its record`() {
        store.save(ActiveCall("d", "gso-a", 1))
        store.save(ActiveCall("d", "gso-a", 2))
        assertEquals(listOf(ActiveCall("d", "gso-a", 2)), store.loadAll())
    }

    @Test
    fun `the record the old app wrote reads as a set of one, and is moved into the set by the next write`() {
        prefs.edit().putString("door_id", "old-door").putString("call_id", "gso-old").putLong("claimed_at_ms", 5).commit()
        assertEquals(listOf(ActiveCall("old-door", "gso-old", 5)), store.loadAll())
        store.save(ActiveCall("d", "gso-new", 9))
        assertEquals(listOf("gso-old", "gso-new"), store.loadAll().map { it.callId })
        assertTrue(!prefs.contains("call_id") && !prefs.contains("door_id") && !prefs.contains("claimed_at_ms"))
    }

    @Test
    fun `taking out the old single record removes it`() {
        prefs.edit().putString("door_id", "old-door").putString("call_id", "gso-old").putLong("claimed_at_ms", 5).commit()
        store.remove("gso-old")
        assertTrue(store.loadAll().isEmpty())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `removing a call that is not there writes nothing`() {
        store.save(ActiveCall("d", "gso-a", 1))
        val commits = prefs.commits
        store.remove("gso-other")
        assertEquals(commits, prefs.commits)
    }

    @Test
    fun `ids with separators in them survive, and a damaged line is skipped`() {
        val odd = ActiveCall("door\twith\ttabs", "call\nnewline %41", 3)
        store.save(odd)
        store.save(ActiveCall("d", "gso-b", 4))
        assertEquals(listOf(odd, ActiveCall("d", "gso-b", 4)), PrefsActiveCallStore(prefs).loadAll())
        prefs.edit().putString("calls_v2", "garbage\n" + prefs.getString("calls_v2", "")).commit()
        assertEquals(2, store.loadAll().size)
    }

    @Test
    fun `clear removes every record`() {
        store.save(ActiveCall("d", "a", 1))
        store.save(ActiveCall("d", "b", 2))
        store.clear()
        assertTrue(store.loadAll().isEmpty())
    }
}
