package com.meda.portico.core.ring

import com.meda.portico.core.push.PushJournalFormat
import java.util.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RingEnvWordsTest {
    @Test fun `ringer and dnd words`() {
        assertEquals("silent", RingEnvWords.ringer(0)); assertEquals("vibrate", RingEnvWords.ringer(1)); assertEquals("normal", RingEnvWords.ringer(2))
        assertEquals("unknown", RingEnvWords.ringer(null))
        assertEquals("off", RingEnvWords.dnd(1)); assertEquals("priority", RingEnvWords.dnd(2)); assertEquals("total", RingEnvWords.dnd(3)); assertEquals("alarms", RingEnvWords.dnd(4))
    }

    @Test fun `sound word tells none from default from custom`() {
        assertEquals("nochan", RingEnvWords.sound(false, true, false))
        assertEquals("none", RingEnvWords.sound(true, true, false))
        assertEquals("default", RingEnvWords.sound(true, false, true))
        assertEquals("custom", RingEnvWords.sound(true, false, false))
    }

    @Test fun `usage words`() {
        assertEquals("alarm", RingEnvWords.usage(4)); assertEquals("notifring", RingEnvWords.usage(6)); assertEquals("unknown", RingEnvWords.usage(null))
    }

    @Test fun `a close reason becomes a legal journal word`() {
        assertEquals("ring-ended", RingEnvWords.word("ring ended"))
        assertEquals("none", RingEnvWords.word(""))
    }

    @Test fun `a full ring-snd line is accepted by the journal format, not turned into question marks`() {
        val line = PushJournalFormat.diagLine(
            0L, TimeZone.getTimeZone("UTC"), "ui", "ring-snd",
            listOf(
                "act" to RingEnvWords.START, "who" to "window", "ringer" to RingEnvWords.ringer(1), "dnd" to RingEnvWords.dnd(1),
                "sound" to RingEnvWords.sound(true, false, true), "usage" to RingEnvWords.usage(4), "valarm" to 3, "valmax" to 7, "vib" to true,
                "why" to RingEnvWords.word("ring ended"),
            ),
        )
        assertTrue(!line.contains("=?"), line)
        assertTrue(line.contains("ringer=vibrate") && line.contains("sound=default") && line.contains("valarm=3") && line.contains("why=ring-ended"), line)
    }
}
