package com.meda.portico.core.content

import com.meda.portico.core.ha.ContentAction
import com.meda.portico.core.ha.ContentBlock
import com.meda.portico.core.ha.ContentCommands
import com.meda.portico.core.ha.ContentPage
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ha.HaInbound
import com.meda.portico.core.ha.HaProtocol
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A Talk ELEMENT carries its own `door` on the action (portico-ha DEV-518) and sits in a block that may have none. The tile's
 * door, and so the TalkRequest's, must be that one: with null the session dials but then ends `via=no_door` once the station answers.
 */
class TalkElementDoorTest {
    private fun frame(blockDoor: String?, actionDoor: String?, kind: String = "talk"): String {
        val bd = blockDoor?.let { ""","door":"$it","door_available":true""" } ?: ""
        val ad = actionDoor?.let { ""","door":"$it"""" } ?: ""
        return """{"id":1,"type":"result","success":true,"result":{"rev":"r1","pages":[{"id":"p","label":"P","blocks":[{"id":"b","title":"B"$bd,"cameras":[],"actions":[{"id":"el-1","label":"Talk","kind":"$kind","available":true,"name":"Talk","reason":null$ad}]}]}]}}"""
    }

    private fun profile(text: String) =
        (ContentCommands.parseGet(HaProtocol.parse(text).single() as HaInbound.Result) as ContentCommands.Outcome.Profile).profile

    private fun tile(p: ContentProfile): ButtonTile =
        HomeModel.build(p, LinkState.CONNECTED, ContentSupport.YES, true, emptySet(), emptySet()).buttons.single()

    @Test
    fun `a Talk element in a block with no door gets the action's own door`() {
        assertEquals("front", tile(profile(frame(blockDoor = null, actionDoor = "front"))).door)
    }

    @Test
    fun `the action's door wins over the block's`() {
        assertEquals("front", tile(profile(frame(blockDoor = "gate", actionDoor = "front"))).door)
    }

    @Test
    fun `a per-block Talk with no door of its own still uses the block's`() {
        assertEquals("gate", tile(profile(frame(blockDoor = "gate", actionDoor = null))).door)
    }

    @Test
    fun `only a talk action reads door`() {
        assertNull(profile(frame(blockDoor = null, actionDoor = "front", kind = "button_press")).pageList.single().actions.single().door)
    }

    @Test
    fun `the cache round trip keeps the action's door`() {
        val p = profile(frame(blockDoor = null, actionDoor = "front"))
        val back = ContentCodec.decode(ContentCodec.encode(p))!!
        assertEquals("front", tile(back).door)
    }

    @Test
    fun `a talk action built by hand in a doorless block uses its door`() {
        val a = ContentAction("t", "Talk", "talk", null, false, null, true, null, null, door = "front")
        assertEquals("front", HomeModel.buttonTile(a, true, emptySet(), null).door)
    }
}
