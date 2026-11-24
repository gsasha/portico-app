package com.meda.portico.core.content

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.CameraSizes
import com.meda.portico.core.ha.ContentAction
import com.meda.portico.core.ha.ContentBlock
import com.meda.portico.core.ha.ContentCamera
import com.meda.portico.core.ha.ContentCommands
import com.meda.portico.core.ha.ContentPage
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ha.HaInbound
import com.meda.portico.core.ha.HaProtocol
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Blocks (DEV-658, B5): the wrap rule, parsing, the grid and button rows, button state and reason, the cache. All pure. */
class BlocksModelTest {

    private fun cam(id: String, size: CameraSize? = null, available: Boolean = true) = ContentCamera(id, id.replaceFirstChar { it.uppercase() }, null, available, size)
    private fun act(
        id: String,
        kind: String = "button_press",
        available: Boolean = true,
        icon: String? = null,
        confirm: Boolean = false,
        reason: String? = null,
        color: String? = null,
        showLabel: Boolean = true,
    ) = ContentAction(id, id.replaceFirstChar { it.uppercase() }, kind, icon, confirm, null, available, null, reason, color, showLabel)

    private fun view(
        page: ContentPage,
        link: LinkState = LinkState.CONNECTED,
        running: Set<String> = emptySet(),
    ) = HomeModel.build(
        ContentProfile("", "r", page.cameras, page.actions, pages = listOf(page)), link, ContentSupport.YES, true, running, emptySet(),
    )

    private fun parse(json: String): ContentProfile {
        val r = HaProtocol.parse("""{"id":1,"type":"result","success":true,"result":$json}""").single() as HaInbound.Result
        return (ContentCommands.parseGet(r) as ContentCommands.Outcome.Profile).profile
    }

    // ---- the wrap rule: a page with no `blocks` (an older server) ----------------------------

    @Test
    fun `a page with no blocks is wrapped into one untitled block with the auto rule - first big, the rest small`() {
        val page = ContentPage("home", "Home", listOf(cam("a"), cam("b"), cam("c")), listOf(act("x")))
        val blocks = page.blockList
        assertEquals(1, blocks.size)
        assertEquals(listOf("a", "b", "c"), blocks.single().cameras.map { it.id })
        assertNull(blocks.single().title, "the page label is the top line; no heading is repeated")
        assertEquals(listOf(CameraSize.BIG, CameraSize.SMALL, CameraSize.SMALL), CameraSizes.effective(blocks.single().cameras))
        val v = view(page).blocks.single()
        assertEquals(listOf(CameraSize.BIG, CameraSize.SMALL, CameraSize.SMALL), v.cameras.map { it.size })
        assertEquals(listOf("x"), v.buttons.map { it.id })
    }

    @Test
    fun `a page with nothing and no blocks key has no block, and blocks present but empty is also none`() {
        assertTrue(ContentPage("p", "P", emptyList(), emptyList()).blockList.isEmpty())
        assertTrue(ContentPage.ofBlocks("p", "P", emptyList()).blockList.isEmpty())
        assertTrue(view(ContentPage.ofBlocks("p", "P", listOf(ContentBlock("b", "Empty", emptyList(), emptyList())))).blocks.isEmpty(), "a block with nothing in it is skipped")
    }

    @Test
    fun `the auto rule keeps a size the server gave and fills only the missing ones`() {
        val sizes = CameraSizes.effective(listOf(cam("a", CameraSize.SMALL), cam("b"), cam("c", CameraSize.BIG)))
        assertEquals(listOf(CameraSize.SMALL, CameraSize.SMALL, CameraSize.BIG), sizes)
    }

    @Test
    fun `with blocks present the flat copy beside them is ignored and every kind of item is in the page`() {
        val p = parse(
            """{"endpoint":"e","kind":"phone","rev":"r","pages":[{"id":"home","label":"Home",
              "blocks":[{"id":"b1","title":"One","cameras":[{"id":"c1","label":"C1","size":"big","available":true}],
                         "actions":[{"id":"a1","label":"A1","kind":"talk","confirm":true,"available":false,"reason":"talk_off"},
                                    {"id":"a2","label":"A2","kind":"cover_open","confirm":true,"available":true}]}],
              "cameras":[{"id":"stale","label":"Stale","available":true}],
              "actions":[{"id":"stale-a","label":"Stale","kind":"button_press","confirm":false,"available":true}]}],"callers":{},"roster":[]}""",
        )
        val page = p.pages.single()
        assertEquals(listOf("c1"), page.cameras.map { it.id })
        assertEquals(listOf("a1", "a2"), page.actions.map { it.id }, "the grant is the blocks' items, not the flat copy")
        assertEquals(listOf("c1"), p.cameras.map { it.id }, "the profile's first-page fields follow the blocks")
        assertNull(p.findAction("stale-a"))
        assertEquals("a1", p.findAction("a1")!!.id)
        assertFalse(p.findAction("a1")!!.confirm, "a talk button never asks first")
    }

    @Test
    fun `blocks parse in order, with title, door and size`() {
        val p = parse(
            """{"rev":"r","pages":[{"id":"home","label":"Home","blocks":[
                 {"id":"b1","title":"Gate","door":"d1","door_available":false,"cameras":[],"actions":[]},
                 {"id":"b2","cameras":[{"id":"c","label":"C","size":"small","available":true}],"actions":[]}]}],"callers":{},"roster":[]}""",
        )
        val (b1, b2) = p.pages.single().blocks!!
        assertEquals(listOf("Gate", null), listOf(b1.title, b2.title))
        assertEquals("d1" to false, b1.door to b1.doorAvailable)
        assertEquals(CameraSize.SMALL, b2.cameras.single().size)
    }

    @Test
    fun `an unknown kind, colour, size or reason is tolerated`() {
        val p = parse(
            """{"rev":"r","pages":[{"id":"home","label":"Home","blocks":[{"id":"b","title":"B",
                 "cameras":[{"id":"c1","label":"C1","size":"gigantic","available":true},{"id":"c2","label":"C2","size":7,"available":true}],
                 "actions":[{"id":"a1","label":"A1","kind":"time_travel","confirm":false,"available":true,"color":"mauve","reason":"because","show_label":"yes"},
                            {"id":"a2","label":"A2","kind":"talk","confirm":false,"available":false,"reason":"on_strike"}]}]}],"callers":{},"roster":[]}""",
        )
        val v = view(p.pages.single()).blocks.single()
        assertEquals(listOf(CameraSize.BIG, CameraSize.SMALL), v.cameras.map { it.size }, "an unreadable size is the auto rule's")
        val a1 = v.buttons[0]
        assertEquals("time_travel", a1.kind)
        assertEquals(ButtonState.READY, a1.state, "an unknown kind is a plain button run by id")
        assertNull(a1.color, "an unknown colour is the default button")
        assertTrue(p.findAction("a1")!!.showLabel, "an unreadable show_label is true")
        assertNull(p.findAction("a1")!!.reason, "a reason on a kind that has none is not kept")
        val a2 = v.buttons[1]
        assertEquals(ButtonState.UNAVAILABLE, a2.state)
        assertNull(a2.reason, "an unknown reason is no reason: the plain unavailable line")
    }

    @Test
    fun `a block or page the document cannot name is dropped without losing the others`() {
        val p = parse(
            """{"rev":"r","pages":[{"id":"home","label":"Home","blocks":["x",{"title":"no id"},{"id":"ok","title":"Ok","cameras":"x","actions":null}]},{"label":"no id"}],"callers":{},"roster":[]}""",
        )
        assertEquals(listOf("ok"), p.pages.single().blocks!!.map { it.id })
    }

    // ---- the camera grid -------------------------------------------------------------------

    private val B = CameraSize.BIG
    private val S = CameraSize.SMALL

    private fun rows(vararg sizes: CameraSize) = CameraGrid.rows(sizes.toList()).map { it.cells }

    @Test
    fun `a big tile takes a row and small ones pair up, in order`() {
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), rows(B, S, S, S, S))
        assertEquals(listOf(listOf(0), listOf(1)), rows(B, B))
        assertEquals(listOf(listOf(0, 1)), rows(S, S))
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `an odd small tile is alone in its row, before a big one or at the end`() {
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3)), rows(S, S, S, B))
        assertEquals(listOf(listOf(0), listOf(1)), rows(S, B), "a lone small before a big one gets its own row")
        val grid = CameraGrid.rows(listOf(B, S, S, S))
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3)), grid.map { it.cells })
        assertEquals(listOf(true, false, false), grid.map { it.big })
        assertEquals(listOf(false, false, true), grid.map { it.lone }, "only the odd one out is left-aligned at half width; a big tile is never lone")
    }

    @Test
    fun `every tile is in exactly one row, in order, for any mix of eight sizes`() {
        for (mask in 0 until 256) {
            val sizes = (0 until 8).map { if ((mask shr it) and 1 == 1) B else S }
            val rows = CameraGrid.rows(sizes)
            assertEquals(sizes.indices.toList(), rows.flatMap { it.cells }, "mask $mask")
            for (row in rows) {
                if (row.big) assertEquals(listOf(B), row.cells.map { sizes[it] }) else assertTrue(row.cells.size in 1..2 && row.cells.all { sizes[it] == S })
            }
        }
    }

    // ---- button rows -----------------------------------------------------------------------

    @Test
    fun `text buttons are two to a row, icon-only four, and a button that does not fit starts a new row`() {
        assertEquals(listOf(listOf(0, 1), listOf(2)), ButtonRows.pack(listOf(2, 2, 2)))
        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(4)), ButtonRows.pack(listOf(1, 1, 1, 1, 1)))
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4)), ButtonRows.pack(listOf(1, 1, 2, 2, 1)))
        assertEquals(listOf(listOf(0), listOf(1)), ButtonRows.pack(listOf(1, 4)))
        assertEquals(listOf(listOf(0), listOf(1, 2)), ButtonRows.pack(listOf(3, 3, 1)))
        assertTrue(ButtonRows.pack(emptyList()).isEmpty())
        assertEquals(2, ButtonRows.units(false))
        assertEquals(1, ButtonRows.units(true))
    }

    @Test
    fun `button rows keep every index once, in order, and never hold more than four units`() {
        val units = listOf(2, 1, 1, 2, 2, 1, 2, 1, 1, 1, 2)
        val rows = ButtonRows.pack(units)
        assertEquals(units.indices.toList(), rows.flatten())
        for (row in rows) assertTrue(row.sumOf { units[it] } <= ButtonRows.PER_ROW, "$row")
        assertEquals(2, ButtonRows.spare(listOf(2), listOf(0)), "a lone text button leaves two units spare")
        assertEquals(0, ButtonRows.spare(listOf(2, 2), listOf(0, 1)))
    }

    @Test
    fun `an out-of-range width is clamped, never an exception or an endless row`() {
        assertEquals(listOf(listOf(0), listOf(1)), ButtonRows.pack(listOf(0, 9)))
        assertEquals(1, ButtonRows.spare(listOf(3), listOf(0)))
        assertEquals(ButtonRows.PER_ROW - 1, ButtonRows.spare(listOf(0), listOf(0)))
        assertEquals(0, ButtonRows.spare(listOf(9), listOf(0)))
    }

    // ---- button state, reason, colour and icon-only --------------------------------------------

    private fun tile(a: ContentAction, link: LinkState = LinkState.CONNECTED, running: Set<String> = emptySet()): ButtonTile {
        val page = ContentPage.ofBlocks("p", "P", listOf(ContentBlock("b", "B", emptyList(), listOf(a))))
        return view(page, link, running).blocks.single().buttons.single()
    }

    @Test
    fun `a talk button the server calls unavailable is off, with its reason when it gave a known one`() {
        for ((wire, reason) in listOf("talk_off" to ButtonReason.TALK_OFF, "no_door" to ButtonReason.NO_DOOR, "not_connected" to ButtonReason.NOT_CONNECTED, "busy" to ButtonReason.BUSY, "phone_only" to ButtonReason.PHONE_ONLY)) {
            val t = tile(act("talk", "talk", available = false, reason = wire))
            assertEquals(ButtonState.UNAVAILABLE, t.state, wire)
            assertEquals(reason, t.reason, wire)
        }
        val noReason = tile(act("talk", "talk", available = false))
        assertEquals(ButtonState.UNAVAILABLE to null, noReason.state to noReason.reason, "no reason text unless the server sends one")
        // B7b: the server's `available: true` is the one thing that makes a Talk button ready (TalkGate); never a confirm step.
        val sayingAvailable = tile(act("talk", "talk", available = true, confirm = true))
        assertEquals(ButtonState.READY, sayingAvailable.state)
        assertFalse(sayingAvailable.confirm)
        assertNull(sayingAvailable.reason)
    }

    @Test
    fun `a button that is off for any other reason shows no reason line, and ready buttons carry none`() {
        assertEquals(ButtonState.UNAVAILABLE to null, tile(act("a", available = false, reason = "talk_off")).let { it.state to it.reason }, "a reason belongs to Talk only")
        assertEquals(ButtonState.READY to null, tile(act("a")).let { it.state to it.reason })
        assertEquals(ButtonState.OFFLINE to null, tile(act("t", "talk", available = false, reason = "talk_off"), LinkState.STOPPED).let { it.state to it.reason }, "offline hides the server's reason")
    }

    @Test
    fun `the new button kinds run like any other once the server says they are available`() {
        for (kind in listOf("lock_lock", "lock_unlock", "cover_open", "cover_close")) {
            assertEquals(ButtonState.READY, tile(act("a", kind)).state, kind)
            assertEquals(ButtonState.UNAVAILABLE, tile(act("a", kind, available = false)).state, kind)
            assertEquals(ButtonState.RUNNING, tile(act("a", kind), running = setOf("a")).state, kind)
            assertTrue(tile(act("a", kind, confirm = true)).confirm, "$kind keeps the server's ask-first flag")
            assertEquals(HomeIcons.KIND_FALLBACK.getValue(kind), HomeIcons.resolve(null, kind))
        }
    }

    @Test
    fun `a known colour is kept lower case, an unknown or missing one is the default button`() {
        assertEquals("red", tile(act("a", color = "red")).color)
        assertEquals("blue", tile(act("a", color = " Blue ")).color)
        assertNull(tile(act("a", color = "mauve")).color)
        assertNull(tile(act("a", color = "")).color)
        assertNull(tile(act("a")).color)
    }

    @Test
    fun `text is hidden only when the server hid it AND an icon will be drawn`() {
        assertTrue(tile(act("a", icon = "mdi:gate", showLabel = false)).iconOnly)
        assertTrue(tile(act("a", "lock_unlock", showLabel = false)).iconOnly, "the kind's own icon counts")
        assertFalse(tile(act("a", "future_kind", showLabel = false)).iconOnly, "no icon to draw: the text stays")
        assertFalse(tile(act("a", icon = "mdi:gate")).iconOnly)
        assertTrue(tile(act("a", icon = "mdi:bogus", showLabel = false)).iconOnly, "an icon the app does not bundle falls to the kind's own icon, so there is one to draw")
        assertFalse(tile(act("a", "future_kind", icon = "mdi:bogus", showLabel = false)).iconOnly)
        // APP-02: a Talk or call button is the phone icon alone, whatever the document says about its label.
        assertTrue(tile(act("a", "talk")).iconOnly, "Talk has no word on it")
        assertTrue(tile(act("a", "call")).iconOnly, "a call button has no word on it")
        assertEquals("phone", HomeIcons.callIcon("talk", false))
        assertEquals("phone-in-talk", HomeIcons.callIcon("call", true))
        assertNull(HomeIcons.callIcon("lock_unlock", false))
    }

    @Test
    fun `a block view lays its cameras and buttons out from the tiles alone`() {
        val page = ContentPage.ofBlocks(
            "p", "P",
            listOf(
                ContentBlock(
                    "b", "  Entrance \n",
                    listOf(cam("a", CameraSize.BIG), cam("b", CameraSize.SMALL), cam("c", CameraSize.SMALL), cam("d", CameraSize.SMALL)),
                    listOf(act("x"), act("y"), act("z", icon = "mdi:gate", showLabel = false), act("w", icon = "mdi:gate", showLabel = false)),
                ),
            ),
        )
        val b = view(page).blocks.single()
        assertEquals("Entrance", b.title)
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3)), b.cameraRows.map { it.cells })
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), b.buttonRows)
    }

    @Test
    fun `a title that is blank after cleaning draws no heading`() {
        val page = ContentPage.ofBlocks("p", "P", listOf(ContentBlock("b", " \u0007 ", listOf(cam("a")), emptyList())))
        assertNull(view(page).blocks.single().title)
    }

    @Test
    fun `Home draws only the selected page's blocks, by id`() {
        val p1 = ContentPage.ofBlocks("p1", "One", listOf(ContentBlock("b1", "A", listOf(cam("c1")), emptyList())))
        val p2 = ContentPage.ofBlocks("p2", "Two", listOf(ContentBlock("b2", "B", listOf(cam("c2")), listOf(act("a2"))), ContentBlock("b3", "C", listOf(cam("c3")), emptyList())))
        val profile = ContentProfile("", "r", p1.cameras, p1.actions, pages = listOf(p1, p2))
        fun v(id: String?) = HomeModel.build(profile, LinkState.CONNECTED, ContentSupport.YES, true, emptySet(), emptySet(), selectedPageId = id)
        assertEquals(listOf("A"), v(null).blocks.map { it.title })
        assertEquals(listOf("B", "C"), v("p2").blocks.map { it.title })
        assertEquals(listOf("c2", "c3"), v("p2").cameras.map { it.id }, "only the visible page's cameras are asked for")
        assertEquals(listOf("A"), v("gone").blocks.map { it.title })
        assertEquals(listOf("One", "Two"), v("p2").tabs.map { it.label }, "page tabs are unchanged")
    }

    // ---- the cache: ONE blob, version 3 --------------------------------------------------------

    private val blocksProfile: ContentProfile = run {
        val page = ContentPage.ofBlocks(
            "home", "Home",
            listOf(
                ContentBlock(
                    "entrance", "Entrance",
                    listOf(cam("gate", CameraSize.BIG), cam("porch", CameraSize.SMALL, available = false)),
                    listOf(act("open", "portico_open", icon = "mdi:gate", confirm = true, color = "red", showLabel = false), act("talk", "talk", available = false, reason = "talk_off")),
                    door = "http://192.168.1.7:8091", doorAvailable = true,
                ),
                ContentBlock("garden", null, listOf(cam("lawn")), emptyList()),
            ),
        )
        ContentProfile("", "r9", page.cameras, page.actions, pages = listOf(page, ContentPage("flat", "Flat", listOf(cam("f")), listOf(act("fa")))))
    }

    @Test
    fun `the cache round trips blocks, sizes, colours, hidden text, doors and reasons - and flat pages stay flat`() {
        val back = ContentCodec.decode(ContentCodec.encode(blocksProfile))!!
        assertEquals(blocksProfile, back)
        assertNull(back.pages[1].blocks, "a page cached without blocks comes back without them (it is wrapped when drawn)")
        val open = back.pages[0].blocks!![0].actions[0]
        assertEquals(Triple("red", false, true), Triple(open.color, open.showLabel, open.confirm))
        assertEquals("talk_off", back.pages[0].blocks!![0].actions[1].reason)
    }

    @Test
    fun `the cache is one JSON blob of version 3`() {
        val text = ContentCodec.encode(blocksProfile)
        assertTrue(text.startsWith("""{"v":3,"""), text.take(20))
        assertEquals(1, Regex("\"v\":3").findAll(text).count())
        assertFalse(text.contains("entity_id") || text.contains("device_id"), "names, ids and flags only")
    }

    @Test
    fun `a cache from before blocks is dropped on the first read, never half-read`() {
        val v2 = """{"v":2,"rev":"r1","pages":[{"id":"main","label":"Main","cameras":[{"id":"gate","label":"Gate","available":true}],"actions":[]}],"callers":{},"roster":[]}"""
        assertNull(ContentCodec.decode(v2))
    }

    @Test
    fun `a cached block with a damaged member keeps the rest`() {
        val text = """{"v":3,"rev":"r","pages":[{"id":"p","label":"P","blocks":[7,{"id":"b","title":"T","cameras":[{"id":"c","label":"C","size":"nope","available":true},{"label":"no id"}],"actions":[{"id":"a","label":"A","kind":"talk","confirm":true,"available":false,"reason":"talk_off","color":"red"}]}]}],"callers":{},"roster":[]}"""
        val p = ContentCodec.decode(text)!!
        val b = p.pages.single().blocks!!.single()
        assertEquals(listOf("c"), b.cameras.map { it.id })
        assertNull(b.cameras.single().size)
        val a = b.actions.single()
        assertEquals(false to "talk_off", a.confirm to a.reason)
    }
}
