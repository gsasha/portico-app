package com.meda.portico.core.content

import com.meda.portico.core.ha.ContentAction
import com.meda.portico.core.ha.ContentBlock
import com.meda.portico.core.ha.ContentCamera
import com.meda.portico.core.ha.ContentCommands
import com.meda.portico.core.ha.ContentPage
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ha.HaInbound
import com.meda.portico.core.ha.HaProtocol
import com.meda.portico.core.ha.RosterEntry
import com.meda.portico.core.ha.WsContract
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M7 3b-A: the roster page against the pager. THE GOLDEN: when the server has house calls off, Home is what it was before this
 * PR - the same tabs, the same selection, the same blocks, no roster - for EVERY recorded document of the contract file and for
 * every link state. And when the server has them on, the page is a last tab.
 */
class HomeModelRosterTest {

    private fun entry(id: String, kind: String = "phone", available: Boolean = true, reason: String? = null, name: String? = id, count: Int? = null) =
        RosterEntry("r-$id", id, kind, name, available, reason, count)

    /** What a server with house calls OFF puts in every roster: the Null registry, `not_available` for each phone, screen and group. */
    private val offRoster = listOf(
        entry("ep-bob", available = false, reason = "not_available"),
        entry("kitchen", "dashboard", available = false, reason = "not_available"),
        entry("gate", "door", available = false, reason = "not_available"),
    )

    private val onRoster = listOf(
        entry("ep-bob", reason = null, name = "Bob's phone"),
        entry("kitchen", "dashboard", available = false, reason = "offline", name = "Kitchen"),
    )

    private fun page(id: String, label: String) =
        ContentPage.ofBlocks(id, label, listOf(ContentBlock("b-$id", "Block $id", listOf(ContentCamera("c-$id", "Cam", null, true)), listOf(ContentAction("a-$id", "Open", "portico_open", null, false, null, true)))))

    private fun profile(roster: List<RosterEntry>, pages: List<ContentPage> = listOf(page("home", "Home"))) =
        ContentProfile(phone = "", rev = "r1", cameras = emptyList(), actions = emptyList(), pages = pages, roster = roster)

    private fun view(p: ContentProfile, link: LinkState = LinkState.CONNECTED, selected: String? = null, calling: String? = null) =
        HomeModel.build(p, link, ContentSupport.YES, true, emptySet(), emptySet(), calling, selected)

    // ---- the golden: off is byte-identical in behaviour and layout ---------------------------------------------------

    @Test
    fun `with house calls off the view is exactly the view of a roster-less profile - one page`() {
        val off = view(profile(offRoster))
        val bare = view(profile(emptyList()))
        assertEquals(bare, off)
        assertNull(off.roster)
        assertEquals(emptyList<PageTab>(), off.tabs, "one page needs no strip, as before")
        assertFalse(off.rosterSelected)
    }

    @Test
    fun `with house calls off the view is exactly the view of a roster-less profile - two pages, every link, every selection`() {
        val pages = listOf(page("home", "Home"), page("garden", "Garden"))
        for (link in LinkState.entries) for (sel in listOf(null, "home", "garden", "nonsense", RosterModel.TAB_ID)) {
            assertEquals(view(profile(emptyList(), pages), link, sel), view(profile(offRoster, pages), link, sel), "$link / $sel")
        }
        assertEquals(listOf("home", "garden"), view(profile(offRoster, pages)).tabs.map { it.id })
    }

    @Test
    fun `the roster tab id cannot be reached when it is off - selecting it falls back to the first page`() {
        val v = view(profile(offRoster), selected = RosterModel.TAB_ID)
        assertEquals("home", v.selectedId)
        assertEquals(0, v.selected)
        assertEquals(1, v.blocks.size)
    }

    @Test
    fun `every recorded document of the contract file with house calls off leaves Home unchanged, and every one with them on gets the page`() {
        var off = 0
        var on = 0
        val names = WsContract.cases.filter { it.type == ContentCommands.GET && it.receive.firstOrNull()?.get("success") == true }.map { it.name }
        for (n in names) {
            val r = HaProtocol.parse(WsContract.reply(n, 1)).single() as HaInbound.Result
            val p = (ContentCommands.parseGet(r) as? ContentCommands.Outcome.Profile)?.profile ?: continue
            val enabled = HouseGate.enabled(p.roster)
            val expectedOn = "(house calls on)" in n && p.roster.isNotEmpty()
            assertEquals(expectedOn, enabled, "$n: the gate follows the server's word, and the gate row alone never turns it on")
            val shown = view(p)
            if (enabled) {
                on++
                assertNotNull(shown.roster, n)
                assertEquals(p.pageList.size + 1, shown.tabs.size, "$n: every document page is a tab, and the roster is one more")
            } else {
                off++
                assertEquals(view(p.copy(roster = emptyList())), shown, "$n: house calls off - Home is what it was")
                assertNull(shown.roster, n)
            }
        }
        assertTrue(off >= 5, "the file has several recorded documents with house calls off ($off)")
        assertTrue(on >= 1, "and at least one with them on ($on)")
    }

    // ---- on: the page is a last tab ---------------------------------------------------------------------------------

    @Test
    fun `with house calls on the roster is the last tab, even beside a single page`() {
        val v = view(profile(onRoster))
        assertEquals(listOf("home", RosterModel.TAB_ID), v.tabs.map { it.id })
        assertEquals("Call", v.tabs.last().label)
        assertEquals(0, v.selected)
        assertEquals("home", v.selectedId)
        assertFalse(v.rosterSelected)
        assertEquals(1, v.blocks.size, "the document page is still the page shown")
    }

    @Test
    fun `selecting the roster tab draws rows instead of blocks, and the swipe can step to it and back`() {
        val pages = listOf(page("home", "Home"), page("garden", "Garden"))
        val v = view(profile(onRoster, pages), selected = RosterModel.TAB_ID)
        assertTrue(v.rosterSelected)
        assertEquals(emptyList<BlockView>(), v.blocks)
        assertEquals(2, v.selected)
        assertEquals("Call", v.pageLabel)
        assertEquals(listOf(false, false, true), TopLine.highlighted(v.tabs, v.selectedId))
        assertEquals(2, Pager.step(v.tabs.size, 1, +1), "one swipe from the last document page reaches the roster")
        assertEquals(2, Pager.step(v.tabs.size, 2, +1), "and no wrap-around")
        assertEquals(1, Pager.step(v.tabs.size, 2, -1))
    }

    @Test
    fun `a phone whose document has no page still reaches the roster - it is the page`() {
        val v = view(profile(onRoster, emptyList()))
        assertTrue(v.rosterSelected)
        assertEquals(RosterModel.TAB_ID, v.selectedId)
        assertEquals(listOf(RosterModel.TAB_ID), v.tabs.map { it.id })
    }

    @Test
    fun `the top line is the tab strip as soon as the roster adds a tab`() {
        val v = view(profile(onRoster))
        assertEquals(TopLine.Tabs, TopLine.choose(false, v.tabs.size, v.pageLabel, v.homeName))
        assertEquals(TopLine.Title("Home"), TopLine.choose(false, view(profile(offRoster)).tabs.size, "Home", null), "off: one page, one plain title, as before")
    }

    @Test
    fun `a live call from a row marks that row CALLING and no other, and the link going down turns every row off`() {
        val calling = view(profile(onRoster), selected = RosterModel.TAB_ID, calling = "r-ep-bob").roster!!
        assertEquals(RowState.CALLING, calling.rows.single { it.id == "ep-bob" }.state)
        assertEquals(RowState.DISABLED, calling.rows.single { it.id == "kitchen" }.state, "the kitchen is offline")
        val down = view(profile(onRoster), link = LinkState.CONNECTING, selected = RosterModel.TAB_ID).roster!!
        assertTrue(down.rows.all { it.state == RowState.DISABLED && it.status == RosterStatus.NOT_CONNECTED }, "nothing is queued for later")
    }
}
