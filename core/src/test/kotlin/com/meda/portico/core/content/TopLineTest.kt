package com.meda.portico.core.content

import com.meda.portico.core.ha.ContentPage
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The one Home top line: what its title slot shows for 0, 1 and several pages, paused, and which tab is highlighted. Sample names only. */
class TopLineTest {

    private fun page(id: String, label: String) = ContentPage(id, label, emptyList(), emptyList())

    private fun view(pages: List<ContentPage>, selected: String? = null) = HomeModel.build(
        ContentProfile("", "r1", emptyList(), emptyList(), homeName = "Villa", pages = pages),
        LinkState.CONNECTED, ContentSupport.YES, true, emptySet(), emptySet(), null, selected,
    )

    private fun line(paused: Boolean, v: HomeView) = TopLine.choose(paused, v.tabs.size, v.pageLabel, v.homeName)

    @Test
    fun `one page shows its label as the line`() {
        assertEquals(TopLine.Title("Example"), line(false, view(listOf(page("p1", "Example")))))
    }

    @Test
    fun `two or more pages make the line the selector`() {
        assertEquals(TopLine.Tabs, line(false, view(listOf(page("p1", "Example"), page("p2", "Garden")))))
        assertEquals(TopLine.Tabs, TopLine.choose(false, 5, "x", null))
    }

    @Test
    fun `paused takes the line from the selector and from the title`() {
        assertEquals(TopLine.Paused, line(true, view(listOf(page("p1", "A"), page("p2", "B")))))
        assertEquals(TopLine.Paused, line(true, view(listOf(page("p1", "A")))))
        assertEquals(TopLine.Paused, line(true, view(emptyList())))
    }

    @Test
    fun `no pages shows the home name, and nothing at all the fallback`() {
        assertEquals(TopLine.Title("Villa"), line(false, view(emptyList())))
        assertEquals(TopLine.Fallback, TopLine.choose(false, 0, null, null))
    }

    @Test
    fun `the highlighted tab is the one selected by id, the first when the id is unknown`() {
        val tabs = view(listOf(page("p1", "A"), page("p2", "B"), page("p3", "C"))).tabs
        assertEquals(listOf(true, false, false), TopLine.highlighted(tabs, null))
        assertEquals(listOf(false, true, false), TopLine.highlighted(tabs, "p2"))
        assertEquals(listOf(false, false, true), TopLine.highlighted(tabs, "p3"))
        assertEquals(listOf(true, false, false), TopLine.highlighted(tabs, "gone"))
        assertEquals(emptyList<Boolean>(), TopLine.highlighted(emptyList(), "p1"))
    }
}
