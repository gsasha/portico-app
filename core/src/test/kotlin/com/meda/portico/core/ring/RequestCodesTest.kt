package com.meda.portico.core.ring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** P2 plan §3.4: one request-code registry, provably free of collisions (filterEquals ignores extras). */
class RequestCodesTest {

    @Test
    fun `every slot x action request code is unique`() {
        val all = (0 until RequestCodes.SLOTS).flatMap { s -> PiAction.values().map { a -> RequestCodes.requestCode(s, a) } }
        assertEquals(RequestCodes.SLOTS * PiAction.values().size, all.toSet().size, "a request code is reused")
    }

    @Test
    fun `the actions fit in a slot's stride`() {
        assertTrue(PiAction.values().all { it.offset in 0 until RequestCodes.STRIDE })
        assertEquals(PiAction.values().size, PiAction.values().map { it.offset }.toSet().size)
    }

    @Test
    fun `notification ids are unique per slot and kind, and never the test notification's`() {
        val ids = (0 until RequestCodes.SLOTS).flatMap { listOf(RequestCodes.ringNotificationId(it), RequestCodes.statusNotificationId(it)) }
        assertEquals(RequestCodes.SLOTS * 2, ids.toSet().size)
        assertTrue(RequestCodes.TEST_NOTIFICATION_ID !in ids)
    }

    @Test
    fun `request codes and notification ids live in disjoint ranges`() {
        val codes = (0 until RequestCodes.SLOTS).flatMap { s -> PiAction.values().map { RequestCodes.requestCode(s, it) } }.toSet()
        val ids = (0 until RequestCodes.SLOTS).flatMap { listOf(RequestCodes.ringNotificationId(it), RequestCodes.statusNotificationId(it)) }.toSet() +
            RequestCodes.TEST_NOTIFICATION_ID
        assertTrue(codes.intersect(ids).isEmpty())
    }

    @Test
    fun `out of range slots are refused`() {
        assertThrows<IllegalArgumentException> { RequestCodes.requestCode(-1, PiAction.DECLINE) }
        assertThrows<IllegalArgumentException> { RequestCodes.requestCode(RequestCodes.SLOTS, PiAction.DECLINE) }
        assertThrows<IllegalArgumentException> { RequestCodes.ringNotificationId(RequestCodes.SLOTS) }
    }

    @Test
    fun `different doors get different slots, the same door the same slot (deterministic)`() {
        val slots = DoorSlots(InMemoryDoorSlotStore())
        val a = slots.slotFor("01J9GATE")
        val b = slots.slotFor("01J9ENTRANCE")
        assertNotEquals(a, b)
        assertEquals(a, slots.slotFor("01J9GATE"))
        assertEquals(listOf(0, 1), listOf(a, b), "first free slot, in order of first sight")
    }

    @Test
    fun `no collisions across calls and actions - two concurrent calls on two doors share no code`() {
        val slots = DoorSlots(InMemoryDoorSlotStore())
        val callOnGate = PiAction.values().map { RequestCodes.requestCode(slots.slotFor("gate"), it) }
        val callOnEntrance = PiAction.values().map { RequestCodes.requestCode(slots.slotFor("entrance"), it) }
        assertTrue(callOnGate.intersect(callOnEntrance.toSet()).isEmpty())
        // A second call on the gate REPLACES the first call's ring (one live call per door), so it
        // reuses the gate's codes on purpose; the call id rides in the extras and is checked.
        val secondCallOnGate = PiAction.values().map { RequestCodes.requestCode(slots.slotFor("gate"), it) }
        assertEquals(callOnGate, secondCallOnGate)
    }

    @Test
    fun `slots survive a process death`() {
        val store = InMemoryDoorSlotStore()
        val first = DoorSlots(store)
        first.slotFor("a"); first.slotFor("b"); val c = first.slotFor("c")
        val again = DoorSlots(store)
        assertEquals(c, again.slotFor("c"))
        assertEquals(3, again.slotFor("d"), "the next free slot, not one already held")
    }

    @Test
    fun `a full table evicts the least recently used door, never two live doors on one slot`() {
        val slots = DoorSlots(InMemoryDoorSlotStore())
        for (i in 0 until RequestCodes.SLOTS) slots.slotFor("door$i")
        slots.slotFor("door0") // door0 used again: door1 is now the least recently used
        val newcomer = slots.slotFor("new")
        assertEquals(1, newcomer)
        assertNull(slots.peek("door1"))
        assertEquals(0, slots.peek("door0"))
        val held = (0 until RequestCodes.SLOTS).map { i -> "door$i" }.filter { it != "door1" }.map { slots.peek(it) } + newcomer
        assertEquals(RequestCodes.SLOTS, held.toSet().size)
    }

    @Test
    fun `a corrupt stored table is ignored, never a crash`() {
        val slots = DoorSlots(InMemoryDoorSlotStore("not;a|table\n99:x:y"))
        assertEquals(0, slots.slotFor("gate"))
    }
}
