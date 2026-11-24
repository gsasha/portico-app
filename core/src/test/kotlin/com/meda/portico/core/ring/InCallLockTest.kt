package com.meda.portico.core.ring

import com.meda.portico.core.call.CallAudioConfig
import com.meda.portico.core.call.KvStore
import com.meda.portico.core.ha.ContentBlock
import com.meda.portico.core.ring.InCallLock.AfterBio
import com.meda.portico.core.ring.InCallLock.AfterUnlock
import com.meda.portico.core.ring.InCallLock.BioAuth
import com.meda.portico.core.ring.InCallLock.BioResult
import com.meda.portico.core.ring.InCallLock.DoorPress
import com.meda.portico.core.ring.InCallLock.UnlockResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InCallLockTest {
    @Test fun `the Unlock control follows the keyguard`() {
        assertTrue(InCallLock.unlockControlVisible(true))
        assertFalse(InCallLock.unlockControlVisible(false))
    }

    @Test fun `the automatic request is once per call, locked and setting on only`() {
        assertTrue(InCallLock.autoRequest(true, true, "c1", null))
        assertFalse(InCallLock.autoRequest(true, true, "c1", "c1"), "never repeated for the same call")
        assertTrue(InCallLock.autoRequest(true, true, "c2", "c1"), "a new call may ask once")
        assertFalse(InCallLock.autoRequest(false, true, "c1", null), "unlocked: nothing to ask")
        assertFalse(InCallLock.autoRequest(true, false, "c1", null), "setting off")
        assertFalse(InCallLock.autoRequest(true, true, null, null), "no call")
    }

    @Test fun `only a success opens the full app`() {
        assertEquals(AfterUnlock.OPEN_FULL_APP, InCallLock.afterUnlock(UnlockResult.OK))
        for (r in listOf(UnlockResult.CANCEL, UnlockResult.ERROR, UnlockResult.TIMEOUT)) assertEquals(AfterUnlock.STAY, InCallLock.afterUnlock(r), r.name)
    }

    @Test fun `a door button while locked needs the biometric, unlocked it runs as today`() {
        assertEquals(DoorPress.NEEDS_BIOMETRIC, InCallLock.doorPress(true))
        assertEquals(DoorPress.RUN, InCallLock.doorPress(false))
        // it is exactly the old rule
        for (locked in listOf(true, false)) assertEquals(DoorUnlockRule.needsUnlock(locked, RingPress.DOOR_ACTION), InCallLock.doorPress(locked) == DoorPress.NEEDS_BIOMETRIC)
    }

    @Test fun `only a successful prompt runs the door action`() {
        assertEquals(AfterBio.RUN_ACTION, InCallLock.afterBio(BioResult.OK))
        assertEquals(AfterBio.NOTHING, InCallLock.afterBio(BioResult.CANCEL))
        assertEquals(AfterBio.NOTHING, InCallLock.afterBio(BioResult.ERROR))
        assertEquals(AfterBio.ASK_KEYGUARD, InCallLock.afterBio(BioResult.UNAVAILABLE))
    }

    @Test fun `the credential fallback is allowed from API 30 only`() {
        assertEquals(BioAuth.STRONG_ONLY, InCallLock.bioAuth(26))
        assertEquals(BioAuth.STRONG_ONLY, InCallLock.bioAuth(29))
        assertEquals(BioAuth.STRONG_OR_DEVICE_CREDENTIAL, InCallLock.bioAuth(30))
        assertEquals(BioAuth.STRONG_OR_DEVICE_CREDENTIAL, InCallLock.bioAuth(36))
    }

    @Test fun `journal words are fixed lowercase words`() {
        val ok = Regex("[a-z0-9_-]{1,32}")
        for (w in UnlockResult.entries.map { it.word } + BioResult.entries.map { it.word } + InCallLock.Source.entries.map { it.word } + DoorPress.entries.map { it.word } + CallDoorTitle.Source.entries.map { it.word }) assertTrue(ok.matches(w), w)
    }

    private class Mem : KvStore {
        val m = HashMap<String, Long>()
        override fun getLong(key: String, default: Long) = m[key] ?: default
        override fun putLong(key: String, value: Long) { m[key] = value }
        override fun getString(key: String, default: String?): String? = default
        override fun putString(key: String, value: String) {}
        override fun remove(key: String) { m.remove(key) }
    }

    @Test fun `Unlock when answering defaults on, round-trips, and is independent of Answer without unlocking`() {
        val c = CallAudioConfig(Mem(), { 0L }, "x")
        assertTrue(c.unlockWhenAnswering())
        c.setUnlockWhenAnswering(false)
        assertFalse(c.unlockWhenAnswering())
        assertTrue(c.answerWithoutUnlock())
        c.setUnlockWhenAnswering(true)
        assertTrue(c.unlockWhenAnswering())
    }

    private fun block(id: String, title: String?, door: String?) = ContentBlock(id, title, emptyList(), emptyList(), door)

    @Test fun `the title is the door's block title, not the driver name`() {
        val t = CallDoorTitle.choose(CallDoorTitle.blockTitleFor("http://192.168.1.7:8091", listOf(block("a", "Front", "x"), block("b", "Gate", "http://192.168.1.7:8091"))), "Portico (gate_sip_observer)")
        assertEquals("Gate", t.text); assertEquals(CallDoorTitle.Source.BLOCK, t.source)
    }

    @Test fun `no block for the door falls back to the doors list name, then to nothing`() {
        assertEquals(CallDoorTitle.Title("Portico (x)", CallDoorTitle.Source.DOOR), CallDoorTitle.choose(CallDoorTitle.blockTitleFor("d", listOf(block("a", null, "d"), block("b", "  ", "d"))), "Portico (x)"))
        assertEquals(CallDoorTitle.Title(null, CallDoorTitle.Source.NONE), CallDoorTitle.choose(null, " "))
        assertNull(CallDoorTitle.blockTitleFor(null, listOf(block("a", "Gate", null))))
    }

    @Test fun `a title from the server is cleaned`() {
        assertEquals("Gate", CallDoorTitle.choose("  Gate\u0000 ", null).text)
    }
}
