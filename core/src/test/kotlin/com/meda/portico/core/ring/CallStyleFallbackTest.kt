package com.meda.portico.core.ring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** PR-G (ship checklist): a refused CallStyle falls back to a plain ring AND is remembered for Setup. */
class CallStyleFallbackTest {
    private val events = ArrayList<String>()
    private val record: (String?) -> Unit = { events += "record ${it ?: "accepted"}" }

    @Test
    fun `accepted - posts once, no plain ring, records accepted (clears an older refusal)`() {
        val r = CallStyleFallback.post({ events += "callstyle" }, { events += "plain" }, record)
        assertNull(r)
        assertEquals(listOf("callstyle", "record accepted"), events)
    }

    @Test
    fun `refused - the plain ring is posted first, then the refusal's class name is recorded`() {
        val r = CallStyleFallback.post({ throw IllegalArgumentException("secret detail") }, { events += "plain" }, record)
        assertEquals("IllegalArgumentException", r)
        assertEquals(listOf("plain", "record IllegalArgumentException"), events, "the ring comes before the bookkeeping; no message text is kept")
    }

    @Test
    fun `refused and the plain ring fails too - the refusal is still recorded and the failure propagates`() {
        assertThrows<IllegalStateException> {
            CallStyleFallback.post({ throw SecurityException() }, { throw IllegalStateException("plain") }, record)
        }
        assertEquals(listOf("record SecurityException"), events)
    }

    @Test
    fun `a failing record never costs the ring`() {
        val r = CallStyleFallback.post({ throw IllegalArgumentException() }, { events += "plain" }, { throw IllegalStateException("disk") })
        assertEquals("IllegalArgumentException", r)
        assertEquals(listOf("plain"), events)
        assertNull(CallStyleFallback.post({ events += "callstyle" }, { events += "plain2" }, { throw IllegalStateException("disk") }))
    }
}
