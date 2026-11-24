package com.meda.portico.core.content

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.ContentCommands
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ha.HaInbound
import com.meda.portico.core.ha.HaProtocol
import com.meda.portico.core.ha.WsContract
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the screen makes of the server's recorded blocks document (`ws_v3.json`, portico-ha B2): the two-block page of
 * "blocks, content get" goes through the real parser and `HomeModel`, so a change of the wire or of the model turns it red.
 */
class BlocksContractTest {
    private val name = "blocks, content get: a phone with two blocks (door, sizes, colour, Talk off, flat form beside them)"

    private val profile: ContentProfile by lazy {
        val r = HaProtocol.parse(WsContract.reply(name, 1)).single() as HaInbound.Result
        (ContentCommands.parseGet(r) as ContentCommands.Outcome.Profile).profile
    }

    private fun view(link: LinkState = LinkState.CONNECTED, pictures: Set<String> = setOf("gate", "porch", "lawn")) =
        HomeModel.build(profile, link, ContentSupport.YES, true, emptySet(), pictures)

    @Test
    fun `Home draws the two recorded blocks with their titles`() {
        val v = view()
        assertEquals(listOf("Entrance", "Garden"), v.blocks.map { it.title })
        assertEquals(listOf("gate", "garage", "porch", "lawn"), v.cameras.map { it.id })
    }

    @Test
    fun `the entrance grid is two big tiles and a lone small one, the garden one lone small`() {
        val (entrance, garden) = view().blocks
        assertEquals(listOf(CameraSize.BIG, CameraSize.BIG, CameraSize.SMALL), entrance.cameras.map { it.size })
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), entrance.cameraRows.map { it.cells })
        assertTrue(entrance.cameraRows[2].lone)
        assertEquals(listOf(listOf(0)), garden.cameraRows.map { it.cells })
        assertTrue(garden.cameraRows.single().lone)
        assertEquals(PictureState.CAMERA_UNAVAILABLE, entrance.cameras[1].picture, "the garage camera is unavailable")
        assertEquals(PictureState.PICTURE, entrance.cameras[0].picture)
    }

    @Test
    fun `the entrance buttons - a text button, a red icon-only unlock, a disabled Talk with its reason, a scene`() {
        val b = view().blocks.first().buttons
        assertEquals(listOf("open-door", "unlock-front", "talk", "evening"), b.map { it.id })
        val open = b[0]
        assertEquals(Triple(ButtonState.READY, true, false), Triple(open.state, open.confirm, open.iconOnly))
        val unlock = b[1]
        assertEquals(Triple("red", true, "lock-open-variant"), Triple(unlock.color, unlock.iconOnly, HomeIcons.resolve(unlock.icon, unlock.kind)))
        assertTrue(unlock.confirm, "lock_unlock asks first")
        val talk = b[2]
        assertEquals(Triple(ButtonState.UNAVAILABLE, ButtonReason.TALK_OFF, false), Triple(talk.state, talk.reason, talk.confirm))
        assertNull(talk.color)
        assertEquals("account-voice", HomeIcons.resolve(talk.icon, talk.kind))
        assertEquals(ButtonState.READY, b[3].state)
        assertTrue(talk.iconOnly, "APP-02: Talk is the phone icon alone")
        assertEquals(listOf(listOf(0, 1, 2), listOf(3)), view().blocks.first().buttonRows, "text (2) + two icons (1 each) fill 4 of 4 units, the next text button (2) starts row two")
    }

    @Test
    fun `the garden gate cover is a green ready button that asks first`() {
        val g = view().blocks[1].buttons.single()
        assertEquals(Triple("green", ButtonState.READY, true), Triple(g.color, g.state, g.confirm))
        assertEquals("garage-open", HomeIcons.resolve(g.icon, g.kind))
    }

    @Test
    fun `offline every button is off and no reason is shown`() {
        val v = view(LinkState.STOPPED)
        assertTrue(v.buttons.all { it.state == ButtonState.OFFLINE })
        assertTrue(v.buttons.all { it.reason == null })
        assertTrue(v.cameras.all { it.picture == PictureState.OFFLINE })
    }

    @Test
    fun `the whole document is the grant - findAction reaches the Talk and cover buttons, not only the flat form`() {
        for (id in listOf("open-door", "unlock-front", "talk", "evening", "gate-cover")) assertEquals(id, profile.findAction(id)?.id, id)
        for (id in listOf("gate", "garage", "porch", "lawn")) assertEquals(id, profile.findCamera(id)?.id, id)
        assertEquals(setOf("gate", "garage", "porch", "lawn"), profile.cameraIds().filter { !it.startsWith("c-") }.toSet())
    }

    @Test
    fun `the recorded document survives the cache`() {
        assertEquals(profile, ContentCodec.decode(ContentCodec.encode(profile)))
        assertFalse(ContentCodec.encode(profile).contains("entity_id"))
    }

    @Test
    fun `the ring screens still get their buttons and cameras unchanged by blocks`() {
        val gate = profile.callers.getValue(WsContract.GATE_ENDPOINT)
        val ring = CallerModel.build(profile, WsContract.GATE_ENDPOINT, CallScreen.RING, LinkState.CONNECTED, ContentSupport.YES, emptySet(), emptySet())!!
        assertEquals(gate.ringButtons.map { it.id }, ring.buttons.map { it.id })
        assertEquals(listOf(CameraSize.BIG), ring.cameras.map { it.size }, "one column, full width")
    }
}
