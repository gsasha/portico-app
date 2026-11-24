package com.meda.portico.core.content

import com.meda.portico.core.ha.ContentPage
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ha.Label
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The Home title is the selected page's label (pure choice plus how the label is cleaned). Sample names only. */
class HomeTitleTest {

    private fun page(id: String, label: String) = ContentPage(id, label, emptyList(), emptyList())

    private fun view(pages: List<ContentPage>, selected: String? = null, homeName: String? = null) = HomeModel.build(
        ContentProfile("", "r1", emptyList(), emptyList(), homeName = homeName, pages = pages),
        LinkState.CONNECTED, ContentSupport.YES, true, emptySet(), emptySet(), null, selected,
    )

    @Test
    fun `paused wins over the page label and the home name`() {
        assertEquals(HomeTitle.Paused, HomeTitle.choose(true, "Example", "Villa"))
        assertEquals(HomeTitle.Paused, HomeTitle.choose(true, null, null))
    }

    @Test
    fun `the selected page's label is the title, ahead of the home name`() {
        assertEquals(HomeTitle.Text("Example"), HomeTitle.choose(false, "Example", "Villa"))
    }

    @Test
    fun `the home name is the title when there is no page label`() {
        assertEquals(HomeTitle.Text("Villa"), HomeTitle.choose(false, null, "Villa"))
    }

    @Test
    fun `with nothing at all the app's own title is the fallback`() {
        assertEquals(HomeTitle.Fallback, HomeTitle.choose(false, null, null))
    }

    @Test
    fun `the view carries the label of the page selected by id and follows the selection`() {
        val pages = listOf(page("p1", "Example"), page("p2", "Garden"))
        assertEquals("Example", view(pages).pageLabel, "no selection is the first page")
        assertEquals("Garden", view(pages, "p2").pageLabel)
        assertEquals("Example", view(pages, "gone").pageLabel, "an unknown id is the first page, as the pager does")
    }

    @Test
    fun `a single page has no tabs but still names the title`() {
        val v = view(listOf(page("p1", "Example")))
        assertEquals(emptyList<PageTab>(), v.tabs)
        assertEquals("Example", v.pageLabel)
    }

    @Test
    fun `no pages gives no label so the name or the fallback shows`() {
        assertNull(view(emptyList()).pageLabel)
        assertEquals(HomeTitle.Text("Villa"), view(emptyList(), homeName = "Villa").let { HomeTitle.choose(false, it.pageLabel, it.homeName) })
        assertEquals(HomeTitle.Fallback, view(emptyList()).let { HomeTitle.choose(false, it.pageLabel, it.homeName) })
    }

    @Test
    fun `the label is cleaned and capped like every other label`() {
        assertEquals("Tavor 22", view(listOf(page("p1", "  Ta‮vor\n22\t "))).pageLabel, "bidi marks go, control characters and runs of space become one space")
        assertEquals(Label.MAX, view(listOf(page("p2", "x".repeat(Label.MAX + 20)))).pageLabel?.length)
        assertNull(view(listOf(page("p3", " \n​ "))).pageLabel, "a blank label is no label: the name or fallback shows, never an empty title")
    }
}
