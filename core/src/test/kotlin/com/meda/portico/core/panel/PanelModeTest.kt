package com.meda.portico.core.panel

import com.meda.portico.core.connlog.JournalLines
import com.meda.portico.core.push.PushJournalFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

/** DEV-807 slice 1: Panel mode's Settings rules, the front reason, the microphone banner, the screen flag, and the `wall` journal word. */
class PanelModeTest {
    @Test
    fun `a phone has every Settings entry open - there is no Panel mode switch to protect`() {
        for (e in SettingsEntry.entries) assertEquals(EntryAccess.OPEN, PanelSettingsGuard.access(false, e), "$e")
        assertTrue(PanelSettingsGuard.restricted(false).isEmpty())
    }

    @Test
    fun `in Panel mode the three entries that could silence the panel are behind a long press and a confirmation`() {
        for (e in listOf(SettingsEntry.STAY_CONNECTED, SettingsEntry.ENROLMENT, SettingsEntry.IDLE_TIMEOUT)) {
            assertEquals(EntryAccess.LONG_PRESS_CONFIRM, PanelSettingsGuard.access(true, e), "$e")
        }
    }

    @Test
    fun `in Panel mode Pause and the ring switch are not offered at all`() {
        assertEquals(EntryAccess.HIDDEN, PanelSettingsGuard.access(true, SettingsEntry.PAUSE))
        assertEquals(EntryAccess.HIDDEN, PanelSettingsGuard.access(true, SettingsEntry.RING_SWITCH))
        assertEquals(EntryAccess.OPEN, PanelSettingsGuard.access(false, SettingsEntry.PAUSE))
        assertEquals(EntryAccess.OPEN, PanelSettingsGuard.access(true, SettingsEntry.OTHER))
    }

    @Test
    fun `every entry has a rule - a new SettingsEntry cannot be forgotten by the guard`() {
        for (panel in listOf(false, true)) for (e in SettingsEntry.entries) PanelSettingsGuard.access(panel, e)
        assertEquals(6, SettingsEntry.entries.size)
    }

    @Test
    fun `front reason - not in front is none, a call outranks a ring, a ring outranks by hand`() {
        assertEquals(FrontReason.NONE, FrontReasons.of(inFront = false, ringUp = true, callLive = true))
        assertEquals(FrontReason.CALL, FrontReasons.of(true, ringUp = true, callLive = true))
        assertEquals(FrontReason.RING, FrontReasons.of(true, ringUp = true, callLive = false))
        assertEquals(FrontReason.HAND, FrontReasons.of(true, ringUp = false, callLive = false))
        assertEquals(listOf("ring", "call", "hand", "none"), FrontReason.entries.map { it.wire })
    }

    @Test
    fun `the microphone banner shows for a live call in Panel mode only, and the screen is held on only in front in Panel mode`() {
        assertTrue(MicBanner.visible(panelMode = true, callLive = true))
        assertFalse(MicBanner.visible(panelMode = true, callLive = false))
        assertFalse(MicBanner.visible(panelMode = false, callLive = true))
        assertTrue(KeepScreenOn.wanted(panelMode = true, inFront = true))
        assertFalse(KeepScreenOn.wanted(panelMode = true, inFront = false))
        assertFalse(KeepScreenOn.wanted(panelMode = false, inFront = true))
    }

    @Test
    fun `the wall journal word is kept by the formatter and by the reporter, the call-start line is clean`() {
        val line = PushJournalFormat.diagLine(
            1_761_000_000_000L, TimeZone.getTimeZone("UTC"), "wall", "call",
            listOf("route" to "speaker", "aec" to true, "ns" to false, "vol" to 7, "mic" to "granted"),
        )
        assertEquals("22:40:00.000 wall call route=speaker aec=yes ns=no vol=7 mic=granted", line)
        val body = line.substringAfter(' ')
        assertTrue(JournalLines.check(body) is JournalLines.Verdict.Keep, "the reporter must keep a wall line")
        // an unknown word is still rewritten to ui by the formatter (the old rule)
        assertTrue(PushJournalFormat.diagLine(0, TimeZone.getTimeZone("UTC"), "walls", "x", emptyList()).contains(" ui "))
    }
}
