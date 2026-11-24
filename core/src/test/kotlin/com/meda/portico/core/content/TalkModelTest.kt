package com.meda.portico.core.content

import com.meda.portico.core.call.EndReason
import com.meda.portico.core.call.TalkErrors
import com.meda.portico.core.call.TalkPhase
import com.meda.portico.core.call.TalkRequest
import com.meda.portico.core.call.TalkSnapshot
import com.meda.portico.core.call.TalkUiState
import com.meda.portico.core.ha.ContentAction
import com.meda.portico.core.ha.ContentBlock
import com.meda.portico.core.ha.ContentPage
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Blocks B7b: the Talk button as Home draws it, from the server's word and this phone's own talk. All pure. */
class TalkModelTest {

    private fun talk(id: String = "talk", available: Boolean = false, reason: String? = "talk_off", kind: String = "talk") =
        ContentAction(id, "Talk", kind, null, false, null, available, null, reason)

    private fun tile(
        a: ContentAction,
        link: LinkState = LinkState.CONNECTED,
        ui: TalkUiState? = null,
        door: String? = "http://192.168.1.7:8091",
    ): ButtonTile {
        val page = ContentPage.ofBlocks("p", "P", listOf(ContentBlock("b", "B", emptyList(), listOf(a), door)))
        val profile = ContentProfile("", "r", page.cameras, page.actions, pages = listOf(page))
        return HomeModel.build(profile, link, ContentSupport.YES, true, emptySet(), emptySet(), talk = ui).blocks.single().buttons.single()
    }

    private fun ui(phase: TalkPhase, id: String = "talk", failure: com.meda.portico.core.call.TalkFailure? = null, reason: EndReason? = null) =
        TalkUiState(TalkRequest(id, "Talk", "d"), TalkSnapshot(phase, reason, failure), 1)

    // ---- the gate: the server's word ---------------------------------------------------------------------

    @Test
    fun `a Talk button is ready only when the server says available, the link is up, and no call of this phone is going`() {
        assertTrue(TalkGate.ready(talk(available = true, reason = null), true))
        assertTrue(TalkGate.ready(talk(available = true, reason = "ended"), true), "right after its own call ended a start is allowed again")
        assertFalse(TalkGate.ready(talk(available = false), true))
        assertFalse(TalkGate.ready(talk(available = true, reason = null), false), "no link")
        assertFalse(TalkGate.ready(null, true))
        assertFalse(TalkGate.ready(talk(available = true, reason = "connecting"), true))
        assertFalse(TalkGate.ready(talk(available = true, reason = "in_call"), true))
        assertFalse(TalkGate.ready(talk(kind = "button_press", available = true, reason = null), true), "only a talk button")
    }

    @Test
    fun `the server's off reasons are never ready`() {
        for (r in listOf("talk_off", "no_door", "not_connected", "busy", "phone_only", null, "something_new")) {
            assertFalse(TalkGate.ready(talk(available = false, reason = r), true), r.toString())
        }
    }

    // ---- the tile: off states are exactly as in B5 --------------------------------------------------------

    @Test
    fun `an unavailable Talk button is the same disabled button with its reason as in B5, whatever the phone's own state`() {
        for ((wire, reason) in listOf("talk_off" to ButtonReason.TALK_OFF, "no_door" to ButtonReason.NO_DOOR, "not_connected" to ButtonReason.NOT_CONNECTED, "busy" to ButtonReason.BUSY, "phone_only" to ButtonReason.PHONE_ONLY)) {
            for (u in listOf(null, TalkUiState(null, TalkSnapshot(TalkPhase.IDLE), 0))) {
                val t = tile(talk(reason = wire), ui = u)
                assertEquals(ButtonState.UNAVAILABLE, t.state, wire)
                assertEquals(reason, t.reason, wire)
                assertNull(t.endLine)
            }
        }
        assertEquals(ButtonState.OFFLINE, tile(talk(), link = LinkState.STOPPED).state, "no link: like every button")
        assertNull(tile(talk(), link = LinkState.STOPPED).reason)
        assertEquals(ButtonState.UNAVAILABLE, tile(talk(reason = null)).state)
    }

    @Test
    fun `an available Talk button is READY and carries the block's door and no confirm`() {
        val t = tile(talk(available = true, reason = null))
        assertEquals(ButtonState.READY, t.state)
        assertEquals("http://192.168.1.7:8091", t.door)
        assertFalse(t.confirm)
        assertNull(t.reason)
        assertNull(tile(talk(available = true, reason = null), door = null).door)
    }

    @Test
    fun `a call of this phone that the server reports going, with no talk of this process behind it, can only be waited out`() {
        for ((wire, reason) in listOf("connecting" to ButtonReason.CONNECTING, "in_call" to ButtonReason.IN_CALL)) {
            val t = tile(talk(available = true, reason = wire))
            assertEquals(ButtonState.UNAVAILABLE, t.state, wire)
            assertEquals(reason, t.reason, wire)
        }
    }

    // ---- the phone's own talk -------------------------------------------------------------------------------

    @Test
    fun `while connecting or in the call the button is the Cancel or Stop button, whatever the server's reason or the link`() {
        for (phase in listOf(TalkPhase.AWAITING_MIC, TalkPhase.STARTING, TalkPhase.CONNECTING)) {
            assertEquals(ButtonState.TALK_CONNECTING, tile(talk(available = true, reason = "connecting"), ui = ui(phase)).state, phase.name)
        }
        assertEquals(ButtonState.TALK_IN_CALL, tile(talk(available = true, reason = "in_call"), ui = ui(TalkPhase.IN_CALL)).state)
        // the server has not caught up yet, or the link is down: Stop must stay reachable
        assertEquals(ButtonState.TALK_IN_CALL, tile(talk(available = false), ui = ui(TalkPhase.IN_CALL)).state)
        assertEquals(ButtonState.TALK_IN_CALL, tile(talk(), link = LinkState.STOPPED, ui = ui(TalkPhase.IN_CALL)).state)
        assertNull(tile(talk(), ui = ui(TalkPhase.IN_CALL)).reason)
    }

    @Test
    fun `another button's talk does not touch this one`() {
        val t = tile(talk(available = false), ui = ui(TalkPhase.IN_CALL, id = "other"))
        assertEquals(ButtonState.UNAVAILABLE, t.state)
        assertEquals(ButtonReason.TALK_OFF, t.reason)
    }

    @Test
    fun `a button that is not a Talk button ignores a talk of the same id`() {
        val t = tile(talk(kind = "button_press", available = true, reason = null), ui = ui(TalkPhase.IN_CALL))
        assertEquals(ButtonState.READY, t.state)
        assertNull(t.door)
        assertNull(t.endLine)
    }

    @Test
    fun `how a talk ended is one line on the button, then the server's state again`() {
        val failed = ui(TalkPhase.ENDED, failure = TalkErrors.noAnswer(), reason = EndReason.FAILED)
        val t = tile(talk(available = true, reason = null), ui = failed)
        assertEquals(ButtonState.READY, t.state, "the button follows the server again at once")
        assertEquals("The gate did not answer.", t.endLine)

        val off = tile(talk(available = false), ui = failed)
        assertEquals(ButtonState.UNAVAILABLE, off.state)
        assertEquals("The gate did not answer.", off.endLine)

        assertNull(tile(talk(available = true, reason = null), ui = ui(TalkPhase.ENDED, reason = EndReason.USER_HANGUP)).endLine, "the user's own stop needs no line")
        assertEquals("The gate hung up.", tile(talk(available = true, reason = null), ui = ui(TalkPhase.ENDED, reason = EndReason.REMOTE_HANGUP)).endLine)
        assertNull(tile(talk(available = true, reason = null), ui = TalkUiState(null, TalkSnapshot(TalkPhase.IDLE), 2)).endLine)
    }

    @Test
    fun `no talk state at all draws the same tiles as before`() {
        assertEquals(tile(talk()), tile(talk(), ui = null))
    }
}
