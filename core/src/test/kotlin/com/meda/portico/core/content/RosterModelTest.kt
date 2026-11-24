package com.meda.portico.core.content

import com.meda.portico.core.ha.RosterEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The roster page's rules, pure (M7 3b-A): the gate, the dot and the words of each reason, and which rows can be pressed. */
class RosterModelTest {

    private fun e(id: String, kind: String = "phone", available: Boolean = true, reason: String? = null, name: String? = id, count: Int? = null) =
        RosterEntry("r-$id", id, kind, name, available, reason, count)

    // ---- THE GATE ------------------------------------------------------------------------------------------------

    @Test
    fun `an empty roster, or one of the Null registry's not_available rows, is off`() {
        assertFalse(HouseGate.enabled(emptyList()))
        assertFalse(HouseGate.enabled(listOf(e("p", available = false, reason = "not_available"), e("k", "dashboard", available = false, reason = "not_available"))))
        assertNull(RosterModel.build(emptyList(), true, null))
    }

    @Test
    fun `a gate row alone never turns the page on - the gate is Talk's path, not a house call`() {
        val gateOnly = listOf(e("gate", "door", available = true, reason = null), e("front", "door", available = false, reason = "phone_only"))
        assertFalse(HouseGate.enabled(gateOnly))
        // and a gate row does not rescue an otherwise-off roster
        assertFalse(HouseGate.enabled(gateOnly + e("p", available = false, reason = "not_available")))
    }

    @ParameterizedTest
    @CsvSource(
        "phone,true,", "phone,false,offline", "phone,false,dnd", "phone,false,busy", "phone,false,receive_off", "phone,true,push_only",
        "dashboard,false,offline", "group,false,no_receivers", "group,true,", "phone,false,disabled", "phone,false,unknown_target",
    )
    fun `any real answer from the house registry turns the page on`(kind: String, available: Boolean, reason: String?) {
        assertTrue(HouseGate.enabled(listOf(e("x", kind, available, reason?.ifEmpty { null }))))
    }

    @Test
    fun `one real row among not_available ones is enough - the server decides per phone`() {
        assertTrue(HouseGate.enabled(listOf(e("a", available = false, reason = "not_available"), e("b", reason = null))))
    }

    // ---- the rows -------------------------------------------------------------------------------------------------

    private fun row(available: Boolean, reason: String?, usable: Boolean = true, calling: String? = null, kind: String = "phone") =
        RosterModel.row(e("x", kind, available, reason), usable, calling)

    @Test
    fun `each reason has its dot, its words and whether the Call button works`() {
        data class Want(val presence: Presence, val status: RosterStatus, val state: RowState)
        val table = mapOf(
            (true to null) to Want(Presence.ONLINE, RosterStatus.AVAILABLE, RowState.READY),
            (true to "push_only") to Want(Presence.PUSH_ONLY, RosterStatus.BY_NOTIFICATION, RowState.READY),
            (false to "offline") to Want(Presence.OFFLINE, RosterStatus.NOT_REACHABLE, RowState.DISABLED),
            (false to "dnd") to Want(Presence.DND, RosterStatus.DO_NOT_DISTURB, RowState.DISABLED),
            (false to "busy") to Want(Presence.BUSY, RosterStatus.ON_A_CALL, RowState.DISABLED),
            (false to "receive_off") to Want(Presence.OFF, RosterStatus.CALLS_OFF, RowState.DISABLED),
            (false to "no_receivers") to Want(Presence.OFF, RosterStatus.NO_RECEIVERS, RowState.DISABLED),
            (false to "not_available") to Want(Presence.OFF, RosterStatus.NOT_AVAILABLE, RowState.DISABLED),
            (false to "unknown_target") to Want(Presence.OFF, RosterStatus.GONE, RowState.DISABLED),
            (false to "disabled") to Want(Presence.OFF, RosterStatus.GONE, RowState.DISABLED),
            (false to "a_reason_from_a_newer_server") to Want(Presence.OFF, RosterStatus.GONE, RowState.DISABLED),
            (false to null) to Want(Presence.OFF, RosterStatus.GONE, RowState.DISABLED),
        )
        for ((k, w) in table) {
            val r = row(k.first, k.second)
            assertEquals(w.presence, r.presence, "$k presence")
            assertEquals(w.status, r.status, "$k status")
            assertEquals(w.state, r.state, "$k state")
        }
    }

    @Test
    fun `Do Not Disturb is told in words - the row is off and says so, before anyone taps`() {
        val r = row(false, "dnd")
        assertEquals(RosterStatus.DO_NOT_DISTURB, r.status)
        assertEquals(RowState.DISABLED, r.state)
    }

    @Test
    fun `not connected - every row is off, grey, and says so - nothing is queued`() {
        for ((a, reason) in listOf(true to null, true to "push_only", false to "dnd")) {
            val r = row(a, reason, usable = false)
            assertEquals(RowState.DISABLED, r.state)
            assertEquals(RosterStatus.NOT_CONNECTED, r.status)
            assertEquals(Presence.OFF, r.presence)
        }
    }

    @Test
    fun `the row whose action id is the live call is CALLING, and a live call outranks a lost link`() {
        val live = RosterModel.row(e("x"), true, "r-x")
        assertEquals(RowState.CALLING, live.state)
        assertEquals(RosterStatus.CALLING, live.status)
        assertEquals(RowState.CALLING, RosterModel.row(e("x"), false, "r-x").state, "its row stays reachable so the call can be reopened")
        assertEquals(RowState.READY, RosterModel.row(e("x"), true, "r-other").state)
    }

    @Test
    fun `Everyone keeps its count and its place, and is named by the cleaned server name`() {
        val v = RosterModel.build(
            listOf(e("everyone", "group", true, null, "Everyone", count = 2), e("p", reason = null, name = "Bob's phone")), true, null,
        )!!
        assertEquals(listOf("everyone", "p"), v.rows.map { it.id }, "the server's order: Everyone first")
        assertTrue(v.rows[0].isEveryone)
        assertEquals(2, v.rows[0].count)
        assertNull(v.rows[1].count)
        assertEquals("Bob's phone", v.rows[1].name)
    }

    @Test
    fun `Everyone with nobody to ring is off with the no-receivers words, never a call that ends declined`() {
        val r = RosterModel.build(listOf(e("everyone", "group", false, "no_receivers", "Everyone", count = 0), e("p", reason = null)), true, null)!!.rows.first()
        assertEquals(RowState.DISABLED, r.state)
        assertEquals(RosterStatus.NO_RECEIVERS, r.status)
    }

    @Test
    fun `door rows are never drawn on the page`() {
        val v = RosterModel.build(listOf(e("p", reason = null), e("gate", "door", reason = null)), true, null)!!
        assertEquals(listOf("p"), v.rows.map { it.id })
    }

    @Test
    fun `a name is cleaned, and a missing one falls back to the endpoint id - never to nothing`() {
        assertEquals("Kitchen", RosterModel.row(e("k", "dashboard", name = "  Kitchen\u0007 "), true, null).name)
        assertEquals("ep-alice", RosterModel.row(e("ep-alice", name = null), true, null).name)
        assertEquals("ep-alice", RosterModel.row(e("ep-alice", name = "   "), true, null).name)
    }
}
