package com.meda.portico.ring

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** A-03, what a host with no device can check: the ring channel is created with the bypass and alarm audio, and the two Do Not Disturb texts agree. Working directory = app/. */
class RingChannelDndTest {
    private val channels = File("src/main/java/com/meda/portico/ring/Channels.kt").readText()
    private val strings = File("src/main/res/values/strings.xml").readText()

    @Test
    fun `the ring channel requests the bypass and the alarm stream, and the old id is deleted`() {
        assertTrue("ring.setBypassDnd(Channel.RING.bypassDnd)" in channels)
        assertTrue("USAGE_ALARM" in channels && "USAGE_NOTIFICATION_RINGTONE" !in channels.substringAfter("object Channels"))
        assertTrue("deleteNotificationChannel(old)" in channels && "RingChannelIds.staleIds(" in channels)
        assertTrue("canBypassDnd()" in channels, "Setup reads the real state")
    }

    @Test
    fun `no text says a door call simply rings through Do Not Disturb`() {
        val dnd = Regex("""name="(st_dnd_on|setup_house_dnd_hint)">([^<]*)""").findAll(strings).map { it.groupValues[2] }.toList()
        assertTrue(dnd.size == 2)
        for (t in dnd) assertTrue("Door calls" in t, "both texts must tie the door call to the Door calls override: $t")
        assertTrue("A door call still rings." !in strings && "may silence a door call" !in strings)
    }
}
