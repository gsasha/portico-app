package com.meda.portico.core.ring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Stay connected's channel, request code and notification id share nothing with the door slots or the other fixed ones. */
class ConnectionCodesTest {
    private val doorCodes = (0 until RequestCodes.SLOTS).flatMap { s -> PiAction.values().map { RequestCodes.requestCode(s, it) } }.toSet()
    private val doorNotes = (0 until RequestCodes.SLOTS).flatMap { listOf(RequestCodes.ringNotificationId(it), RequestCodes.statusNotificationId(it)) }.toSet()

    @Test
    fun `the open request code is its own`() {
        val others = listOf(
            RequestCodes.PAUSE_ALARM, RequestCodes.PAUSE_RESUME, RequestCodes.PAUSE_OPEN, RequestCodes.CALL_OPEN, RequestCodes.CALL_HANGUP,
        )
        assertTrue(RequestCodes.CONNECTION_OPEN !in others && RequestCodes.CONNECTION_OPEN !in doorCodes)
    }

    @Test
    fun `the notification id is its own`() {
        val others = listOf(
            RequestCodes.TEST_NOTIFICATION_ID, RequestCodes.PAUSE_NOTIFICATION_ID, RequestCodes.CALL_NOTIFICATION_ID,
        )
        assertTrue(RequestCodes.CONNECTION_NOTIFICATION_ID !in others && RequestCodes.CONNECTION_NOTIFICATION_ID !in doorNotes)
    }

    @Test
    fun `the channel is a versioned LOW id of its own, never a ringing one`() {
        assertEquals("portico_connection_v1", Channel.CONNECTION.id)
        assertEquals(Importance.LOW, Channel.CONNECTION.importance)
        assertEquals(Channel.values().size, Channel.values().map { it.id }.toSet().size)
    }
}
