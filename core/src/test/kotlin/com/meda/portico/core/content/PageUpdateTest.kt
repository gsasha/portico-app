package com.meda.portico.core.content

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageUpdateTest {
    private fun cam(id: String, p: PictureState = PictureState.PICTURE) = CameraTile(id, id, null, p)
    private fun btn(id: String, s: ButtonState) = ButtonTile(id, id, null, "talk", null, false, s)
    private fun view(vararg blocks: BlockView) = HomeView(Banner.NONE, blocks.toList())
    private fun block(cams: List<CameraTile>, b: ButtonTile) = BlockView("b", "T", cams, listOf(b))

    @Test fun `a call starting or ending changes only the buttons`() {
        val idle = view(block(listOf(cam("c1")), btn("t", ButtonState.READY)))
        val call = view(block(listOf(cam("c1")), btn("t", ButtonState.TALK_IN_CALL)))
        assertTrue(PageUpdate.onlyButtonsChanged(idle, call))
        assertTrue(PageUpdate.onlyButtonsChanged(call, idle))
    }

    @Test fun `a camera change, a block change, no change or no previous view is a full redraw`() {
        val a = view(block(listOf(cam("c1")), btn("t", ButtonState.READY)))
        assertFalse(PageUpdate.onlyButtonsChanged(a, view(block(listOf(cam("c1", PictureState.LOADING)), btn("t", ButtonState.TALK_IN_CALL)))))
        assertFalse(PageUpdate.onlyButtonsChanged(a, view(block(listOf(cam("c1"), cam("c2")), btn("t", ButtonState.READY)))))
        assertFalse(PageUpdate.onlyButtonsChanged(a, a))
        assertFalse(PageUpdate.onlyButtonsChanged(null, a))
    }

    @Test fun `scroll reveal moves just enough`() {
        assertEquals(0, ScrollReveal.delta(100, 300, 800))
        assertEquals(150, ScrollReveal.delta(500, 950, 800))
        assertEquals(-40, ScrollReveal.delta(-40, 100, 800))
        assertEquals(50, ScrollReveal.delta(50, 1000, 800))
    }
}
