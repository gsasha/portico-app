package com.meda.portico.core.content

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallBarPlacementTest {
    private fun tile(id: String, kind: String, state: ButtonState) =
        ButtonTile(id = id, label = id, detail = null, kind = kind, icon = null, confirm = false, state = state)

    private fun block(id: String, vararg b: ButtonTile) = BlockView(id, null, emptyList(), b.toList())

    @Test fun `a talk or call button with a call under way is the anchor`() {
        for (s in listOf(ButtonState.CALLING, ButtonState.TALK_CONNECTING, ButtonState.TALK_IN_CALL, ButtonState.RINGING_IN)) {
            assertTrue(CallBarPlacement.isCallTile(tile("t", "talk", s)), "talk $s")
            assertTrue(CallBarPlacement.isCallTile(tile("c", "call", s)), "call $s")
        }
    }

    @Test fun `a ready button or another kind is not`() {
        assertFalse(CallBarPlacement.isCallTile(tile("t", "talk", ButtonState.READY)))
        assertFalse(CallBarPlacement.isCallTile(tile("t", "talk", ButtonState.UNAVAILABLE)))
        assertFalse(CallBarPlacement.isCallTile(tile("o", "open", ButtonState.RUNNING)))
    }

    @Test fun `the bar goes under the block and button of the live call`() {
        val blocks = listOf(
            block("a", tile("open", "open", ButtonState.READY), tile("talk1", "talk", ButtonState.READY)),
            block("b", tile("open2", "open", ButtonState.READY), tile("talk2", "talk", ButtonState.TALK_IN_CALL)),
        )
        assertEquals(1 to 1, CallBarPlacement.find(blocks))
        assertEquals(1, CallBarPlacement.indexIn(blocks[1].buttons))
        assertNull(CallBarPlacement.indexIn(blocks[0].buttons))
    }

    @Test fun `no call button on the page keeps the bar at the bottom`() {
        assertNull(CallBarPlacement.find(listOf(block("a", tile("talk1", "talk", ButtonState.READY)))))
        assertNull(CallBarPlacement.find(emptyList()))
    }
}
