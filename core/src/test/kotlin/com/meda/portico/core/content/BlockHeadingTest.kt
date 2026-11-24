package com.meda.portico.core.content

import com.meda.portico.core.ha.ContentAction
import com.meda.portico.core.ha.ContentBlock
import com.meda.portico.core.ha.ContentPage
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A block heading that repeats the page name (already on the top line) is not drawn; any other keeps its words. Sample names only. */
class BlockHeadingTest {

    @Test
    fun `a heading equal to the page label is left out`() {
        assertNull(BlockHeading.choose("Example", "Example"))
    }

    @Test
    fun `trim and case are ignored when comparing`() {
        assertNull(BlockHeading.choose("Example", "  example "))
        assertNull(BlockHeading.choose(" EXAMPLE", "Example"))
    }

    @Test
    fun `a heading that differs from the page label is kept as given`() {
        assertEquals("Entrance", BlockHeading.choose("Example", "Entrance"))
        assertEquals("Example garden", BlockHeading.choose("Example", "Example garden"))
    }

    @Test
    fun `no title or a blank one means no heading`() {
        assertNull(BlockHeading.choose("Example", null))
        assertNull(BlockHeading.choose("Example", ""))
        assertNull(BlockHeading.choose(null, "   "))
    }

    @Test
    fun `with no page label every real title is kept`() {
        assertEquals("Entrance", BlockHeading.choose(null, "Entrance"))
        assertEquals("Entrance", BlockHeading.choose("  ", "Entrance"))
    }

    // ---- through HomeModel.build ----

    private fun block(id: String, title: String?) =
        ContentBlock(id, title, emptyList(), listOf(ContentAction("a-$id", "Gate", "portico_open", "gate", false, null, true)))

    private fun view(vararg blocks: ContentBlock, label: String = "Example", extra: ContentPage? = null): HomeView {
        val pages = listOfNotNull(ContentPage("p1", label, emptyList(), emptyList(), blocks.toList()), extra)
        return HomeModel.build(
            ContentProfile("", "r1", emptyList(), emptyList(), homeName = "Villa", pages = pages),
            LinkState.CONNECTED, ContentSupport.YES, true, emptySet(), emptySet(), null, null,
        )
    }

    @Test
    fun `Home drops the one block's heading that repeats its page, with one page or several`() {
        assertEquals(listOf<String?>(null), view(block("b1", "Example")).blocks.map { it.title })
        val two = view(block("b1", "example"), extra = ContentPage("p2", "Garden", emptyList(), emptyList()))
        assertEquals(2, two.tabs.size)
        assertEquals(listOf<String?>(null), two.blocks.map { it.title })
    }

    @Test
    fun `a page with several blocks keeps the distinct titles`() {
        val v = view(block("b1", "Entrance"), block("b2", "Example"), block("b3", "Garage"))
        assertEquals(listOf("Entrance", null, "Garage"), v.blocks.map { it.title })
    }
}
