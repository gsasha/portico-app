package com.meda.portico.core.content

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.ContentAction
import com.meda.portico.core.ha.ContentBlock
import com.meda.portico.core.ha.ContentCamera
import com.meda.portico.core.ha.ContentPage
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 0.5.5-alpha: "when I minimize a camera, the items following it disappear". Pins what the PURE layers guarantee after
 * the first (big) camera of a page is tapped small: every camera, button and block of the page is still in the view the
 * screen draws, each camera sits in exactly one grid cell, and every one has its picture. So the loss on the phone was
 * not in the model: it was the view tree rebuilt inside the tap (HomeActivity.toggleSize, see SizesZoomResourcesTest).
 */
class ShrinkKeepsEverythingTest {
    private fun cam(id: String) = ContentCamera(id, id, null, true, null) // no size: the auto rule, first of a block big
    private fun act(id: String) = ContentAction(id, id, "button_press", null, false, null, true)
    private val profile = ContentProfile(
        "", "r", emptyList(), emptyList(),
        pages = listOf(
            ContentPage.ofBlocks(
                "p", "P",
                listOf(
                    ContentBlock("b1", "Front", listOf(cam("c1"), cam("c2"), cam("c3"), cam("c4"), cam("c5")), listOf(act("a1"), act("a2"))),
                    ContentBlock("b2", "Back", listOf(cam("d1"), cam("d2")), listOf(act("a3"))),
                    ContentBlock("b3", "Buttons only", emptyList(), listOf(act("a4"))),
                ),
            ),
        ),
    )
    private val pictures = setOf("c1", "c2", "c3", "c4", "c5", "d1", "d2")

    private class MemStore : SizeStore {
        var text: String? = null
        override fun load() = text
        override fun save(text: String): Boolean {
            this.text = text
            return true
        }
    }

    private fun build(memory: SizeMemory) =
        HomeModel.build(profile, LinkState.CONNECTED, ContentSupport.YES, true, emptySet(), pictures, null, "p", memory)

    @Test
    fun `tapping the first camera small keeps every other camera, button and block, each camera in one cell, each with its picture`() {
        val memory = SizeMemory(MemStore())
        val before = build(memory)
        assertEquals(CameraSize.BIG, before.blocks[0].cameras[0].size)

        val first = before.blocks[0].cameras[0]
        assertEquals(CameraSize.SMALL, memory.toggle("p", first.id, first.defaultSize))
        val after = build(memory)

        assertEquals(CameraSize.SMALL, after.blocks[0].cameras[0].size)
        assertEquals(before.blocks.map { it.id }, after.blocks.map { it.id }, "no block lost")
        assertEquals(before.cameras.map { it.id }, after.cameras.map { it.id }, "no camera lost, same order")
        assertEquals(before.buttons.map { it.id }, after.buttons.map { it.id }, "no button lost")
        for ((b, a) in before.blocks.zip(after.blocks)) {
            assertEquals(b.title, a.title)
            assertEquals(b.buttons, a.buttons)
            assertEquals(b.cameras.drop(1), a.cameras.drop(1), "the cameras after the tapped one are unchanged")
        }
        for (block in after.blocks) {
            val placed = CameraGrid.rows(block.cameras.map { it.size }).flatMap { it.cells }
            assertEquals(block.cameras.indices.toList(), placed, "every camera of ${block.id} in exactly one cell, in order")
        }
        assertTrue(after.cameras.all { it.picture == PictureState.PICTURE }, "every camera still has its picture (nothing is shown as loading)")
    }
}
