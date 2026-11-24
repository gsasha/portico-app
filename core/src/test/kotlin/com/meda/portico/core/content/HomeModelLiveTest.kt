package com.meda.portico.core.content

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.ContentBlock
import com.meda.portico.core.ha.ContentCamera
import com.meda.portico.core.ha.ContentPage
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-713: Home's tiles carry the document's `live` and its view the document's `show_fps` (what the live code decides from). */
class HomeModelLiveTest {
    private fun profile(showFps: Boolean) = ContentProfile(
        "", "r1", emptyList(), emptyList(),
        pages = listOf(
            ContentPage.ofBlocks(
                "p", "P",
                listOf(ContentBlock("b", null, listOf(ContentCamera("gate", "Gate", null, true, CameraSize.BIG, live = true), ContentCamera("lawn", "Lawn", null, true, CameraSize.SMALL)), emptyList())),
            ),
        ),
        showFps = showFps,
    )

    private fun view(p: ContentProfile) = HomeModel.build(p, LinkState.CONNECTED, ContentSupport.YES, true, emptySet(), emptySet())

    @Test
    fun `a tile carries the document's live flag`() {
        val tiles = view(profile(false)).cameras
        assertEquals(listOf(true, false), tiles.map { it.live })
        assertEquals(listOf("gate", "lawn"), tiles.map { it.id })
    }

    @Test
    fun `the view carries show_fps, off by default`() {
        assertTrue(view(profile(true)).showFps)
        assertFalse(view(profile(false)).showFps)
    }

    @Test
    fun `show_fps changes the view, so a document edit redraws the tiles`() {
        assertFalse(view(profile(true)) == view(profile(false)))
    }
}
