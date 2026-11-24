package com.meda.portico.core.call

import com.meda.portico.core.call.HouseRingCoordinator.Dismiss
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The house ring's rules (M7 3b-B, design 7, 15.1 HC-2/HC-4/HC-5, 15.13): when a ring is shown, what ends it, and that only a press answers. */
class HouseRingCoordinatorTest {

    private val r = HouseRingRig()

    // ---- the ring, from the socket ----------------------------------------------------------------------------------

    @Test
    fun `a house_call ringing addressed to this phone shows the ring with the document's name for the caller`() {
        r.ring()
        val ring = r.shown.single()
        assertEquals("hso-1", ring.callId)
        assertEquals("Kitchen screen", ring.name)
        assertEquals(HouseRing.Source.SOCKET, ring.source)
        assertEquals("hso-1", r.c.state().ring!!.callId)
    }

    @Test
    fun `this phone's own placed call, a screen's group copy and a ring for someone else are not rings for this phone`() {
        r.ring(from = "ep-alice", to = "ep-bob") // my own call to bob: from me
        r.ring(callId = "hso-2", from = "kitchen", to = "everyone") // a screen's own copy of a group call
        r.ring(callId = "hso-3", from = "kitchen", to = "ep-bob") // addressed to another phone
        assertEquals(emptyList<HouseRing>(), r.shown)
        assertNull(r.c.state().ring)
    }

    @Test
    fun `a caller the document does not name is shown as no name - never as its id`() {
        r.ring(from = "ep-stranger")
        assertNull(r.shown.single().name)
        assertEquals("Someone", HouseRingCoordinator.SOMEONE)
        // nothing the coordinator publishes holds the raw endpoint id
        assertFalse(r.shown.toString().contains("ep-stranger"))
        assertFalse(r.journalText.contains("ep-stranger"))
    }

    @Test
    fun `a snapshot ring after a reconnect is a ring too, once - a repeat is stale`() {
        r.ring(snapshot = true)
        r.ring(snapshot = true)
        assertEquals(1, r.shown.size)
    }

    // ---- the rules, one by one --------------------------------------------------------------------------------------

    @Test
    fun `house calls off for this phone - silent, nothing shown`() {
        r.enabled = false
        r.ring()
        assertEquals(emptyList<HouseRing>(), r.shown)
        assertTrue("result=silent_off" in r.journalText)
    }

    @Test
    fun `receive is the phone's own switch - off (the default) means silent`() {
        r.receiveOn = false
        r.ring()
        assertEquals(emptyList<HouseRing>(), r.shown)
        assertTrue("result=silent_receive_off" in r.journalText)
    }

    @Test
    fun `Android Do Not Disturb silences a house ring - a house call never rings through it`() {
        r.dndOn = true
        r.ring()
        assertEquals(emptyList<HouseRing>(), r.shown)
        assertTrue("result=silent_dnd" in r.journalText)
    }

    @Test
    fun `a paused phone does not ring for a house call`() {
        r.paused = true
        r.ring()
        assertEquals(emptyList<HouseRing>(), r.shown)
        assertTrue("result=silent_paused" in r.journalText)
    }

    @Test
    fun `a door outranks a house call - a house ring while a door rings or a door call is live or held is silent, dropped, not queued`() {
        r.doorBusy = true
        r.ring()
        r.pushRing()
        assertEquals(emptyList<HouseRing>(), r.shown)
        assertTrue("result=silent_door_busy" in r.journalText)
        // the door is over: the house ring that was dropped does NOT come back by itself (nothing is queued)
        r.doorBusy = false
        assertNull(r.c.state().ring)
        assertEquals(emptyList<HouseRing>(), r.shown)
    }

    @Test
    fun `one call at a time - a house ring while another call of this phone is live is silent, the phone never switches`() {
        r.callLive = true
        r.ring()
        assertEquals(emptyList<HouseRing>(), r.shown)
        assertTrue("result=silent_busy" in r.journalText)
    }

    @Test
    fun `a second house ring replaces the first (a different call id replaces it)`() {
        r.ring("hso-1")
        r.ring("hso-2")
        assertEquals("hso-2", r.c.state().ring!!.callId)
        assertEquals(listOf("hso-1" to Dismiss.REPLACED), r.dismissed)
    }

    // ---- what ends a ring ------------------------------------------------------------------------------------------

    @Test
    fun `ended, answered elsewhere and active for the ringing call withdraw it`() {
        r.ring("hso-1")
        r.c.onHouseCall(HouseCallEvent("hso-1", "ended", "answered_elsewhere", "kitchen", "ep-alice"))
        assertEquals(listOf("hso-1" to Dismiss.ANSWERED_ELSEWHERE), r.dismissed)
        r.ring("hso-2")
        r.c.onHouseCall(HouseCallEvent("hso-2", "ended", "cancelled", "kitchen", "ep-alice"))
        assertEquals("hso-2" to Dismiss.ENDED, r.dismissed.last())
        r.ring("hso-3")
        r.c.onHouseCall(HouseCallEvent("hso-3", "answered", null, "kitchen", "ep-alice"))
        assertEquals("hso-3" to Dismiss.ANSWERED_ELSEWHERE, r.dismissed.last())
    }

    @Test
    fun `an end for another call changes nothing`() {
        r.ring("hso-1")
        r.c.onHouseCall(HouseCallEvent("hso-9", "ended", "hangup", "kitchen", "ep-alice"))
        assertEquals("hso-1", r.c.state().ring!!.callId)
        assertEquals(emptyList<Pair<String, Dismiss>>(), r.dismissed)
    }

    @Test
    fun `a door ring withdraws a house ring on this phone, silently`() {
        r.ring()
        r.c.onDoorRinging()
        assertNull(r.c.state().ring)
        assertEquals(listOf("hso-1" to Dismiss.DOOR), r.dismissed)
        assertEquals(0, r.cmds.sent.size, "nothing is told to the server: it cancels the house ring itself")
    }

    @Test
    fun `a ring from the socket ends with the socket, a pushed ring does not`() {
        r.ring("hso-1")
        r.c.onLinkLost()
        assertEquals(listOf("hso-1" to Dismiss.LINK_LOST), r.dismissed)
        r.pushRing()
        r.c.onLinkLost()
        assertNotNull(r.c.state().ring, "a push-sourced ring stays until its own window ends")
    }

    @Test
    fun `a ring expires with its window (45 s plus the skew) and its timer is cancelled by an earlier end`() {
        r.ring()
        assertEquals(1, r.sched.pending)
        r.sched.advance(59_000)
        assertNotNull(r.c.state().ring)
        r.sched.advance(2_000)
        assertNull(r.c.state().ring)
        assertEquals("hso-1" to Dismiss.EXPIRED, r.dismissed.single())
        r.ring("hso-2")
        r.c.onHouseCall(HouseCallEvent("hso-2", "ended", null, "kitchen", "ep-alice"))
        assertEquals(0, r.sched.pending, "no timer outlives its ring")
    }

    @Test
    fun `switching house calls off withdraws a ringing call`() {
        r.ring()
        r.c.onSwitchedOff()
        assertNull(r.c.state().ring)
    }

    // ---- the press -----------------------------------------------------------------------------------------------------

    @Test
    fun `nothing answers by itself - only the press of Answer does, and then the ring is withdrawn and the answer begins with the name`() {
        r.ring()
        r.sched.advance(10)
        assertEquals(emptyList<Pair<String, String>>(), r.answers, "a ring, an event, a timer never answers")
        r.c.answer("hso-1")
        assertEquals(listOf("hso-1" to "Kitchen screen"), r.answers)
        assertEquals("hso-1" to Dismiss.ANSWERED_HERE, r.dismissed.single())
        assertNull(r.c.state().ring)
        assertEquals(0, r.cmds.sent.size, "the answer command is the session's, not the coordinator's")
    }

    @Test
    fun `an unnamed caller is answered as Someone`() {
        r.ring(from = "ep-stranger")
        r.c.answer("hso-1")
        assertEquals(listOf("hso-1" to "Someone"), r.answers)
    }

    @Test
    fun `an Answer for a call that is not ringing does nothing`() {
        r.ring("hso-1")
        r.c.answer("hso-other")
        r.c.answer("hso-1")
        r.c.answer("hso-1")
        assertEquals(1, r.answers.size)
    }

    @Test
    fun `a door that rang while the screen was up outranks the press - the ring goes and nothing is answered`() {
        r.ring()
        r.doorBusy = true
        r.c.answer("hso-1")
        assertEquals(emptyList<Pair<String, String>>(), r.answers)
        assertEquals("hso-1" to Dismiss.DOOR, r.dismissed.single())
    }

    @Test
    fun `Decline tells the house with the call id alone, and withdraws the ring here`() {
        r.ring()
        r.c.decline("hso-1")
        val d = r.cmds.sent.single()
        assertEquals("portico/house/decline", d.type)
        assertEquals(obj("call_id" to "hso-1"), d.body)
        assertEquals("hso-1" to Dismiss.DECLINED, r.dismissed.single())
        d.ok(kotlinx.serialization.json.buildJsonObject {})
        assertEquals(emptyList<Pair<String, String>>(), r.answers)
    }

    // ---- the push tail -------------------------------------------------------------------------------------------------

    @Test
    fun `a pushed house ring resolves from_ref through the document and is never shown raw`() {
        r.pushRing()
        val ring = r.shown.single()
        assertEquals("Bob's phone", ring.name)
        assertEquals(HouseRing.Source.PUSH, ring.source)
        assertFalse(ring.toString().contains(HouseCallers.refFor("ep-bob")), "the reference is not in what the screen is given")
        assertFalse(r.journalText.contains(HouseCallers.refFor("ep-bob")))
        assertFalse(r.journalText.contains("Bob"), "the journal holds no name")
    }

    @Test
    fun `a pushed ring from a reference the document does not know is Someone, never the reference`() {
        r.pushRing(fromRef = "e-0123456789abcdef0123456789abcdef")
        assertNull(r.shown.single().name)
        assertFalse(r.shown.toString().contains("0123456789abcdef"))
    }

    @Test
    fun `a pushed ring that arrives after its window is silent, and its ended before the ring makes the ring stale`() {
        r.now = 1_759_700_060_001L
        r.pushRing()
        assertEquals(emptyList<HouseRing>(), r.shown)
        assertTrue("result=silent_late" in r.journalText)
        val r2 = HouseRingRig()
        r2.c.onPush(HousePush.Ended("hso-x", HouseCallers.refFor("ep-bob"), 3, 1_759_700_006_000L), r2.now)
        r2.c.onPush(HousePush.Ring("hso-x", HouseCallers.refFor("ep-bob"), 1, 1_759_700_000_000L, 1_759_700_045_000L), r2.now)
        assertEquals(emptyList<HouseRing>(), r2.shown)
        assertTrue("result=silent_stale" in r2.journalText)
    }

    @Test
    fun `the same call by the socket and by push rings once`() {
        r.ring("hso-1")
        r.c.onPush(HousePush.Ring("hso-1", HouseCallers.refFor("kitchen"), 1, 1_759_700_000_000L, 1_759_700_045_000L), r.now)
        assertEquals(1, r.shown.size)
    }

    @Test
    fun `a pushed answered or ended withdraws the matching ring and no other`() {
        r.pushRing("hso-a")
        r.c.onPush(HousePush.Ended("hso-b", HouseCallers.refFor("ep-bob"), 3, 1L), r.now)
        assertNotNull(r.c.state().ring)
        r.c.onPush(HousePush.Answered("hso-a", HouseCallers.refFor("ep-bob"), 2, 1L), r.now)
        assertNull(r.c.state().ring)
        assertEquals("hso-a" to Dismiss.ANSWERED_ELSEWHERE, r.dismissed.single())
    }

    @Test
    fun `the journal holds shortened call ids, outcomes and no endpoint id, reference or name`() {
        r.ring("hso-1759700000000000000-1-a1b2c3")
        r.c.answer("hso-1759700000000000000-1-a1b2c3")
        val text = r.journalText
        assertTrue("house ring call=hso-17" in text, text)
        assertTrue("house answer call=hso-17" in text)
        assertTrue("house over call=hso-17" in text && "result=answered_here" in text)
        assertFalse("a1b2c3" in text, "never the whole id")
        assertFalse("Kitchen" in text, "no name")
    }
}
