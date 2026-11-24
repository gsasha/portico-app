package com.meda.portico.core.ring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pause's PendingIntent request codes and notification id share nothing with the door slots. */
class PauseCodesTest {
    private val doorCodes = (0 until RequestCodes.SLOTS).flatMap { s -> PiAction.values().map { RequestCodes.requestCode(s, it) } }.toSet()
    private val doorNotes = (0 until RequestCodes.SLOTS).flatMap { listOf(RequestCodes.ringNotificationId(it), RequestCodes.statusNotificationId(it)) }.toSet()

    @Test
    fun `the pause's request codes are distinct and outside every door slot`() {
        val pause = listOf(RequestCodes.PAUSE_ALARM, RequestCodes.PAUSE_RESUME, RequestCodes.PAUSE_OPEN)
        assertEquals(3, pause.toSet().size)
        assertTrue(pause.none { it in doorCodes })
    }

    @Test
    fun `the paused notification id is its own`() {
        assertTrue(RequestCodes.PAUSE_NOTIFICATION_ID !in doorNotes)
        assertTrue(RequestCodes.PAUSE_NOTIFICATION_ID != RequestCodes.TEST_NOTIFICATION_ID)
    }

    @Test
    fun `the paused channel is its own id, LOW, never a ringing channel`() {
        assertEquals("portico_paused_v1", Channel.PAUSED.id)
        assertEquals(Importance.LOW, Channel.PAUSED.importance)
        assertEquals(Channel.values().size, Channel.values().map { it.id }.toSet().size)
    }
}
