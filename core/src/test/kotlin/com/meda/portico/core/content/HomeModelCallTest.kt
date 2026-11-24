package com.meda.portico.core.content

import com.meda.portico.core.ha.CallTarget
import com.meda.portico.core.ha.ContentAction
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** How a call button is drawn: the state from the server's reason, never a confirm, off when not connected. */
class HomeModelCallTest {
    private fun call(id: String = "talk", available: Boolean = true, reason: String? = null, confirm: Boolean = false) =
        ContentAction(id, "Kitchen", "call", "mdi:phone-in-talk", confirm, "Kitchen tablet", available, CallTarget("dashboard", "ep-1"), reason)

    private fun build(
        vararg actions: ContentAction,
        link: LinkState = LinkState.CONNECTED,
        support: ContentSupport = ContentSupport.YES,
        running: Set<String> = emptySet(),
        calling: String? = null,
    ) = HomeModel.build(ContentProfile("p", "r", emptyList(), actions.toList()), link, support, true, running, emptySet(), calling).buttons

    @Test
    fun `an available call button is ready and says nothing of confirming`() {
        val t = build(call()).single()
        assertEquals(ButtonState.READY, t.state)
        assertEquals("call", t.kind)
        assertFalse(t.confirm)
    }

    @Test
    fun `a call button never asks first, even if a server says it should`() {
        assertFalse(build(call(confirm = true)).single().confirm)
        // while any other kind still can
        val lock = ContentAction("l", "Gate", "lock_open", null, true, null, true)
        assertEquals(true, build(lock).single().confirm)
    }

    @Test
    fun `each reason gives its own off state`() {
        val expected = mapOf(
            "offline" to ButtonState.TARGET_OFFLINE,
            "push_only" to ButtonState.TARGET_OFFLINE, // CB-7: off until the push ring for calls exists
            "busy" to ButtonState.TARGET_BUSY,
            "dnd" to ButtonState.TARGET_DND,
            "receive_off" to ButtonState.TARGET_RECEIVE_OFF,
            "not_available" to ButtonState.CALLS_NOT_AVAILABLE,
            "unknown_target" to ButtonState.TARGET_GONE,
            "disabled" to ButtonState.TARGET_GONE,
            "something_new" to ButtonState.TARGET_GONE,
        )
        for ((reason, state) in expected) assertEquals(state, build(call(available = false, reason = reason)).single().state, reason)
        assertEquals(ButtonState.TARGET_GONE, build(call(available = false, reason = null)).single().state)
    }

    @Test
    fun `not connected turns every call button off, whatever its reason`() {
        assertEquals(ButtonState.OFFLINE, build(call(), link = LinkState.CONNECTING).single().state)
        assertEquals(ButtonState.OFFLINE, build(call(available = false, reason = "busy"), link = LinkState.STOPPED).single().state)
        assertEquals(ButtonState.OFFLINE, build(call(), support = ContentSupport.NO).single().state)
    }

    @Test
    fun `the button of a live call shows it and the others stay as they were`() {
        val tiles = build(call("a"), call("b"), calling = "a")
        assertEquals(ButtonState.CALLING, tiles[0].state)
        assertEquals(ButtonState.READY, tiles[1].state)
        assertEquals(ButtonState.READY, build(call("a"), calling = null).single().state)
    }

    @Test
    fun `a non-call button is unchanged by all of this`() {
        val scene = ContentAction("s", "Evening", "scene_activate", null, false, "Evening", false)
        assertEquals(ButtonState.UNAVAILABLE, build(scene).single().state)
        assertEquals(ButtonState.RUNNING, build(scene.copy(available = true), running = setOf("s")).single().state)
    }

    @Test
    fun `the call icon falls back to phone-in-talk`() {
        assertEquals("phone-in-talk", HomeIcons.resolve(null, "call"))
        assertEquals("phone-in-talk", HomeIcons.resolve("mdi:junk", "call"))
        assertEquals("doorbell", HomeIcons.resolve("mdi:doorbell", "call"))
    }
}
