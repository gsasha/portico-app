package com.meda.portico.core.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RedactTest {
    // A fixture shaped like an FCM registration token (163 chars); not a real one.
    private val registration = "fixtureRegToken0123456789:APA91b" + "Q".repeat(120) + "ENDofFIXTURE"

    @Test
    fun regIsSha256FirstEightHex() {
        // sha256("abc") = ba7816bf... (FIPS 180-2, B.1)
        assertEquals("ba7816bf", Redact.reg("abc"))
        assertEquals(8, Redact.reg(registration).length)
        assertEquals(Redact.NONE, Redact.reg(null))
        assertEquals(Redact.NONE, Redact.reg(""))
    }

    @Test
    fun prefixIsSixCharsAndLength() {
        assertEquals("gso-17…(32)", Redact.prefix("gso-1759700000000000000-1-a1b2c3"))
        assertEquals("…(3)", Redact.prefix("abc"))
        assertEquals(Redact.NONE, Redact.prefix(null))
    }

    @Test
    fun aFullRegistrationNeverAppearsInARenderedLogLine() {
        val line = PushLog.registration("fid", registration, "3f1e2d4c-0000-4000-8000-000000000000")
        assertFalse(registration in line, line)
        // Not even a long run of it: no 12-character window of the value survives.
        assertFalse(registration.windowed(12).any { it in line }, line)
        assertTrue(Redact.reg(registration) in line, line)
    }

    @Test
    fun decisionLinesCarryNoGarbledValue() {
        // A registration pasted into a data field by a bug is not echoed by the parser's journal line.
        val data = mapOf("pv" to "2", "kind" to "ring", "door_ref" to registration, "call_id" to "c", "seq" to "1",
            "t" to "1", "exp" to "2")
        val msg = PushMessage.parse(data)
        val line = PushLog.decision(msg, Decision.receive(InMemoryCallStore(), msg, 0, setOf("d")))
        assertFalse(registration in line, line)
        assertTrue("door_ref" in line, line)
    }
}
